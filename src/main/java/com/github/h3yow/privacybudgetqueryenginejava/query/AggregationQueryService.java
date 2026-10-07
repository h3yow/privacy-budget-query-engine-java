package com.github.h3yow.privacybudgetqueryenginejava.query;

import com.github.h3yow.privacybudgetqueryenginejava.data.GroupSummary;
import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.noise.LaplaceMechanism;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.InvalidPrivacyParameterException;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyParameters;
import com.github.h3yow.privacybudgetqueryenginejava.session.BudgetSession;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * 聚合查询编排，严格按以下顺序执行，保证各路径可解释且预算守恒：
 *
 * <ol>
 *   <li>会话前置校验（仅当请求携带 sessionId）—— 会话不存在/已结束/已过期
 *       直接以 {@link QueryStatus#REJECTED_SESSION_INVALID} 拒绝，不触碰预算；</li>
 *   <li>隐私参数校验 —— 越界直接拒绝，不触碰预算；</li>
 *   <li>最小匿名度前置校验 —— 分组规模不足直接拒绝，不消耗预算；</li>
 *   <li>预算原子扣减 —— 无会话时扣域账本，有会话时扣会话额度；余额不足拒绝，状态不变；</li>
 *   <li>扰动输出 —— 扣减后若发生异常则回滚预算，且绝不返回未扰动原始值。</li>
 * </ol>
 *
 * <p>兼容性：不带 sessionId 的请求走原有路径，响应结构与扰动结果与之前完全一致。
 */
public class AggregationQueryService {

    private static final Logger log = LoggerFactory.getLogger(AggregationQueryService.class);

    private final LocalDataSource dataSource;
    private final Map<String, PrivacyBudget> budgets;
    private final SessionManager sessionManager;

    /** 兼容旧装配：无会话管理器时，携带 sessionId 的请求一律按会话失效拒绝。 */
    public AggregationQueryService(LocalDataSource dataSource, Map<String, PrivacyBudget> budgets) {
        this(dataSource, budgets, null);
    }

    public AggregationQueryService(LocalDataSource dataSource, Map<String, PrivacyBudget> budgets,
                                   SessionManager sessionManager) {
        this.dataSource = dataSource;
        this.budgets = budgets;
        this.sessionManager = sessionManager;
    }

    public QueryResult query(QueryRequest request) {
        log.info("收到查询请求: domain={}, groupKey={}, epsilon={}, delta={}, minGroupSize={}, seed={}, sessionId={}",
                request.domain(), request.groupKey(), request.epsilon(), request.delta(),
                request.minGroupSize(), request.seed(), request.sessionId());

        PrivacyBudget budget = budgets.get(request.domain());
        if (budget == null) {
            return reject(request, QueryStatus.REJECTED_INVALID_PARAMETER, 0.0,
                    "未知数据域: " + request.domain());
        }

        // 0. 会话前置校验（不触碰预算）
        BudgetSession session = null;
        if (request.sessionId() != null) {
            session = sessionManager == null ? null : sessionManager.resolveActive(request.sessionId());
            if (session == null) {
                return reject(request, QueryStatus.REJECTED_SESSION_INVALID, budget.remaining(),
                        "会话不存在、已结束或已过期: " + request.sessionId());
            }
            if (!session.domain().equals(request.domain())) {
                return reject(request, QueryStatus.REJECTED_INVALID_PARAMETER, budget.remaining(),
                        "会话属于数据域 " + session.domain() + "，不能用于 " + request.domain());
            }
        }

        // 1. 隐私参数校验（不触碰预算）
        try {
            new PrivacyParameters(request.epsilon(), request.delta()).validate();
        } catch (InvalidPrivacyParameterException e) {
            return reject(request, QueryStatus.REJECTED_INVALID_PARAMETER, remainingFor(session, budget),
                    e.getMessage());
        }

        // 2. 最小匿名度前置校验（不消耗预算）
        GroupSummary summary = dataSource.summarize(request.domain(), request.groupKey());
        if (summary.count() < request.minGroupSize()) {
            return reject(request, QueryStatus.REJECTED_INSUFFICIENT_ANONYMITY, remainingFor(session, budget),
                    "分组规模 " + summary.count() + " 小于最小匿名度 " + request.minGroupSize());
        }

        // 3. 预算原子扣减（并发安全，余额不足则状态不变）
        boolean consumed = session != null
                ? session.tryConsume(request.epsilon())
                : budget.tryConsume(request.epsilon());
        if (!consumed) {
            return reject(request, QueryStatus.REJECTED_BUDGET_EXHAUSTED, remainingFor(session, budget),
                    "预算不足: 请求 epsilon=" + request.epsilon() + ", 剩余=" + remainingFor(session, budget));
        }
        log.info("预算扣减: domain={}, sessionId={}, -{} epsilon, 剩余={}",
                request.domain(), request.sessionId(), request.epsilon(), remainingFor(session, budget));

        // 4. 扰动输出；任何异常都回滚预算，且绝不返回原始值
        try {
            double noisyValue = perturb(request, summary);
            log.info("查询成功: domain={}, groupKey={}, sessionId={}, epsilonConsumed={}, 剩余额度={}",
                    request.domain(), request.groupKey(), request.sessionId(),
                    request.epsilon(), remainingFor(session, budget));
            return QueryResult.ok(noisyValue, request.epsilon(), remainingFor(session, budget));
        } catch (RuntimeException e) {
            if (session != null) {
                session.refund(request.epsilon());
            } else {
                budget.refund(request.epsilon());
            }
            log.warn("扰动阶段异常，预算已回滚: domain={}, sessionId={}, +{} epsilon, 剩余={}, 原因={}",
                    request.domain(), request.sessionId(), request.epsilon(),
                    remainingFor(session, budget), e.toString());
            return QueryResult.rejected(QueryStatus.REJECTED_INVALID_PARAMETER, remainingFor(session, budget),
                    "内部处理异常，预算已回滚: " + e.getMessage());
        }
    }

    /** 响应中报告的余额：会话路径报告会话剩余额度，否则报告域账本剩余（保持旧语义）。 */
    private double remainingFor(BudgetSession session, PrivacyBudget budget) {
        return session != null ? session.held() : budget.remaining();
    }

    /** 用请求种子派生确定性噪声并加到聚合值上。 */
    private double perturb(QueryRequest request, GroupSummary summary) {
        long derivedSeed = request.seed()
                ^ Objects.hash(request.domain(), request.groupKey());
        double scale = LocalDataSource.MAX_VALUE / request.epsilon();
        return summary.sum() + new LaplaceMechanism(derivedSeed).sample(scale);
    }

    private QueryResult reject(QueryRequest request, QueryStatus status, double remaining, String reason) {
        log.info("查询拒绝: domain={}, groupKey={}, sessionId={}, status={}, 剩余={}, 原因={}",
                request.domain(), request.groupKey(), request.sessionId(), status, remaining, reason);
        return QueryResult.rejected(status, remaining, reason);
    }

    /** 暴露账本只读视图，供测试与审计校验守恒性。 */
    public double remainingBudget(String domain) {
        PrivacyBudget budget = budgets.get(domain);
        return budget == null ? 0.0 : budget.remaining();
    }
}
