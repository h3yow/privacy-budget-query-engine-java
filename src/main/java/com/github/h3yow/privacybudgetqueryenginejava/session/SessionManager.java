package com.github.h3yow.privacybudgetqueryenginejava.session;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.InvalidPrivacyParameterException;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyParameters;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话额度管理器：申请、查找、主动结束与空闲超时回收。
 *
 * <p>申请时通过 {@link PrivacyBudget#tryReserve} 原子地把域可用预算转为占用，
 * 因此申请量不会超过该域当前实际可用预算，并发申请也不会超占。
 *
 * <p>回收有两条路径，都汇聚到 {@link BudgetSession} 的一次性状态迁移，
 * 不会重复归还：定时 {@link #reapExpired()}，以及访问时的惰性过期检查。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, PrivacyBudget> budgets;
    private final Map<String, BudgetSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long defaultIdleTimeoutMillis;

    public SessionManager(Map<String, PrivacyBudget> budgets, Clock clock, long defaultIdleTimeoutMillis) {
        this.budgets = budgets;
        this.clock = clock;
        this.defaultIdleTimeoutMillis = defaultIdleTimeoutMillis;
    }

    /** 为数据域申请一段额度；参数非法或域可用预算不足时返回可区分的拒绝。 */
    public CreateSessionResponse create(String domain, double epsilon, Long idleTimeoutMillis) {
        PrivacyBudget budget = budgets.get(domain);
        if (budget == null) {
            log.info("会话申请拒绝: domain={}, 原因=未知数据域", domain);
            return CreateSessionResponse.rejected(QueryStatus.REJECTED_INVALID_PARAMETER,
                    "未知数据域: " + domain);
        }
        try {
            new PrivacyParameters(epsilon, 0.0).validate();
        } catch (InvalidPrivacyParameterException e) {
            log.info("会话申请拒绝: domain={}, epsilon={}, 原因={}", domain, epsilon, e.getMessage());
            return CreateSessionResponse.rejected(QueryStatus.REJECTED_INVALID_PARAMETER, e.getMessage());
        }
        long timeout = idleTimeoutMillis != null ? idleTimeoutMillis : defaultIdleTimeoutMillis;
        if (timeout <= 0) {
            log.info("会话申请拒绝: domain={}, idleTimeoutMillis={}, 原因=超时必须为正", domain, timeout);
            return CreateSessionResponse.rejected(QueryStatus.REJECTED_INVALID_PARAMETER,
                    "空闲超时必须为正: " + timeout);
        }
        if (!budget.tryReserve(epsilon)) {
            log.info("会话申请拒绝: domain={}, epsilon={}, 可用预算={}", domain, epsilon, budget.available());
            return CreateSessionResponse.rejected(QueryStatus.REJECTED_BUDGET_EXHAUSTED,
                    "域可用预算不足: 申请=" + epsilon + ", 可用=" + budget.available());
        }
        BudgetSession session = new BudgetSession(
                UUID.randomUUID().toString(), domain, epsilon, budget, clock, timeout);
        sessions.put(session.id(), session);
        log.info("会话申请成功: sessionId={}, domain={}, granted={}, 域可用预算={}",
                session.id(), domain, epsilon, budget.available());
        return CreateSessionResponse.ok(session);
    }

    /** 查找仍活跃的会话；发现已空闲超时则顺手回收并返回 null。 */
    public BudgetSession resolveActive(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        BudgetSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        session.expireIfIdle();
        return session.state() == SessionState.ACTIVE ? session : null;
    }

    /** 主动结束会话并归还剩余额度；幂等。 */
    public boolean close(String sessionId) {
        BudgetSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        boolean transitioned = session.close();
        if (transitioned) {
            log.info("会话主动结束: sessionId={}, 剩余额度已归还, 域可用预算={}",
                    sessionId, budgets.get(session.domain()).available());
        }
        return transitioned;
    }

    /** 回收所有空闲超时的会话，返回本次回收数量。 */
    public int reapExpired() {
        int reaped = 0;
        for (BudgetSession session : sessions.values()) {
            if (session.expireIfIdle()) {
                reaped++;
                log.info("会话空闲超时回收: sessionId={}, domain={}, 剩余额度已归还, 域可用预算={}",
                        session.id(), session.domain(), budgets.get(session.domain()).available());
            }
        }
        return reaped;
    }

    public SessionInfo info(String sessionId) {
        BudgetSession session = sessions.get(sessionId);
        return session == null ? null : session.info();
    }
}
