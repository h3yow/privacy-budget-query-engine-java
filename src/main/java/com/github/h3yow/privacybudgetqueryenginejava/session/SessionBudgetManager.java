package com.github.h3yow.privacybudgetqueryenginejava.session;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.InvalidPrivacyParameterException;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话额度生命周期管理：申请、查找、主动结束与空闲超时回收。
 *
 * <p>回收路径：查询时惰性检查（{@link SessionBudget#tryConsume} /
 * {@link SessionBudget#expireIfIdle}）+ 周期性 {@link #sweepExpired()}，
 * 保证空闲会话（含调用方中途断开、异常退出的场景）不会一直占着预算不放；
 * 归还后同一数据域的其他请求立即可用。
 */
public class SessionBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(SessionBudgetManager.class);

    /** 默认空闲超时：60 秒无访问则自动回收。 */
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 60_000L;

    private final Map<String, PrivacyBudget> budgets;
    private final Map<String, SessionBudget> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long defaultIdleTimeoutMillis;

    public SessionBudgetManager(Map<String, PrivacyBudget> budgets) {
        this(budgets, Clock.systemUTC(), DEFAULT_IDLE_TIMEOUT_MILLIS);
    }

    public SessionBudgetManager(Map<String, PrivacyBudget> budgets, Clock clock, long defaultIdleTimeoutMillis) {
        this.budgets = budgets;
        this.clock = clock;
        this.defaultIdleTimeoutMillis = defaultIdleTimeoutMillis;
    }

    /** 以默认空闲超时申请会话额度。 */
    public SessionBudget apply(String domain, double epsilon) {
        return apply(domain, epsilon, defaultIdleTimeoutMillis);
    }

    /**
     * 为某数据域申请一段会话额度：先在账本预留，再登记会话。
     *
     * @throws InvalidPrivacyParameterException 数据域未知、额度越界或超过该域当前可用预算
     */
    public SessionBudget apply(String domain, double epsilon, long idleTimeoutMillis) {
        PrivacyBudget budget = budgets.get(domain);
        if (budget == null) {
            throw new InvalidPrivacyParameterException("未知数据域: " + domain);
        }
        // 复用单次参数的边界校验，防止非法额度进入账本
        new PrivacyParameters(epsilon, 0.0).validate();
        if (idleTimeoutMillis <= 0) {
            throw new InvalidPrivacyParameterException("空闲超时必须为正: " + idleTimeoutMillis);
        }
        if (!budget.tryReserve(epsilon)) {
            throw new InvalidPrivacyParameterException(
                    "申请额度 " + epsilon + " 超过数据域 " + domain + " 当前可用预算 " + budget.remaining());
        }
        SessionBudget session = new SessionBudget(
                UUID.randomUUID().toString(), domain, epsilon, idleTimeoutMillis,
                budget, clock.millis());
        sessions.put(session.id(), session);
        log.info("会话额度申请成功: sessionId={}, domain={}, granted={}, idleTimeoutMs={}, 域剩余可用={}",
                session.id(), domain, epsilon, idleTimeoutMillis, budget.remaining());
        return session;
    }

    /** 按 id 查找会话；不存在返回 null。 */
    public SessionBudget find(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /** 主动结束会话并归还剩余额度；幂等。未知 id 返回 false。 */
    public boolean close(String sessionId) {
        SessionBudget session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        double returned = session.close();
        log.info("会话额度主动结束: sessionId={}, domain={}, 归还={}, 域剩余可用={}",
                sessionId, session.domain(), returned,
                budgets.get(session.domain()).remaining());
        return true;
    }

    /** 回收所有空闲超时的会话（含已关闭但未摘除的），返回本次回收数量。 */
    @Scheduled(initialDelayString = "${privacy.session.sweep-interval-ms:10000}",
            fixedDelayString = "${privacy.session.sweep-interval-ms:10000}")
    public int sweepExpired() {
        long now = clock.millis();
        int swept = 0;
        for (Map.Entry<String, SessionBudget> entry : sessions.entrySet()) {
            SessionBudget session = entry.getValue();
            if (session.expireIfIdle(now) || session.isClosed()) {
                if (sessions.remove(entry.getKey(), session)) {
                    swept++;
                    log.info("会话额度回收: sessionId={}, domain={}, 原因=空闲超时/已关闭, 域剩余可用={}",
                            session.id(), session.domain(),
                            budgets.get(session.domain()).remaining());
                }
            }
        }
        return swept;
    }

    /** 当前仍登记在册的会话数量。 */
    public int activeSessionCount() {
        return sessions.size();
    }

    /** 管理器时钟的当前时间（惰性过期检查与清扫共用同一时钟，便于测试）。 */
    public long nowMillis() {
        return clock.millis();
    }
}
