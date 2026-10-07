package com.github.h3yow.privacybudgetqueryenginejava.query;

import com.github.h3yow.privacybudgetqueryenginejava.data.GroupSummary;
import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.noise.LaplaceMechanism;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.BudgetAudit;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.InvalidPrivacyParameterException;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyParameters;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudget;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudgetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聚合查询编排，严格按以下顺序执行，保证各路径可解释且预算守恒：
 *
 * <ol>
 *   <li>隐私参数校验 —— 越界直接拒绝，不触碰预算；</li>
 *   <li>会话额度前置校验（仅会话请求）—— 会话不存在、已回收或跨域使用直接拒绝；</li>
 *   <li>最小匿名度前置校验 —— 分组规模不足直接拒绝，不消耗预算；</li>
 *   <li>预算原子扣减 —— 老调用方从数据域账本扣，会话请求从会话额度内扣，
 *       余额不足拒绝，状态不变；会话已过期/已结束返回可区分的拒绝；</li>
 *   <li>扰动输出 —— 扣减后若发生异常则回滚扣减，且绝不返回未扰动原始值。</li>
 * </ol>
 */
public class AggregationQueryService {

    private static final Logger log = LoggerFactory.getLogger(AggregationQueryService.class);

    private final LocalDataSource dataSource;
    private final Map<String, PrivacyBudget> budgets;
    private final SessionBudgetManager sessionManager;

    public AggregationQueryService(LocalDataSource dataSource, Map<String, PrivacyBudget> budgets) {
        this(dataSource, budgets, new SessionBudgetManager(budgets));
    }

    public AggregationQueryService(LocalDataSource dataSource, Map<String, PrivacyBudget> budgets,
                                   SessionBudgetManager sessionManager) {
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

        // 1. 隐私参数校验（不触碰预算）
        try {
            new PrivacyParameters(request.epsilon(), request.delta()).validate();
        } catch (InvalidPrivacyParameterException e) {
            return reject(request, QueryStatus.REJECTED_INVALID_PARAMETER, budget.remaining(), e.getMessage());
        }

        // 2. 会话额度前置校验（不消耗预算）：不存在、已回收或跨域使用一律拒绝
        SessionBudget session = null;
        if (request.sessionId() != null) {
            session = sessionManager.find(request.sessionId());
            if (session == null) {
                return reject(request, QueryStatus.REJECTED_SESSION_INVALID, budget.remaining(),
                        "会话额度不存在或已回收: " + request.sessionId());
            }
            if (!session.domain().equals(request.domain())) {
                return reject(request, QueryStatus.REJECTED_SESSION_INVALID, budget.remaining(),
                        "会话 " + request.sessionId() + " 属于数据域 " + session.domain()
                                + "，不能用于 " + request.domain());
            }
        }

        // 3. 最小匿名度前置校验（不消耗预算）
        GroupSummary summary = dataSource.summarize(request.domain(), request.groupKey());
        if (summary.count() < request.minGroupSize()) {
            return reject(request, QueryStatus.REJECTED_INSUFFICIENT_ANONYMITY, budget.remaining(),
                    "分组规模 " + summary.count() + " 小于最小匿名度 " + request.minGroupSize());
        }

        // 4. 预算原子扣减（并发安全，余额不足则状态不变）；会话路径从会话额度内扣
        if (session != null) {
            SessionBudget.ConsumeOutcome outcome =
                    session.tryConsume(request.epsilon(), sessionManager.nowMillis());
            switch (outcome) {
                case EXPIRED:
                    return reject(request, QueryStatus.REJECTED_SESSION_INVALID, budget.remaining(),
                            "会话额度已空闲超时并自动回收: " + request.sessionId());
                case CLOSED:
                    return reject(request, QueryStatus.REJECTED_SESSION_INVALID, budget.remaining(),
                            "会话额度已结束: " + request.sessionId());
                case INSUFFICIENT:
                    return reject(request, QueryStatus.REJECTED_BUDGET_EXHAUSTED, session.remaining(),
                            "会话额度不足: 请求 epsilon=" + request.epsilon()
                                    + ", 会话剩余=" + session.remaining());
                case OK:
                    log.info("会话额度扣减: sessionId={}, domain={}, -{} epsilon, 会话剩余={}",
                            session.id(), request.domain(), request.epsilon(), session.remaining());
                    break;
                default:
                    throw new IllegalStateException("未知消费结果: " + outcome);
            }
        } else if (!budget.tryConsume(request.epsilon())) {
            return reject(request, QueryStatus.REJECTED_BUDGET_EXHAUSTED, budget.remaining(),
                    "预算不足: 请求 epsilon=" + request.epsilon() + ", 剩余=" + budget.remaining());
        } else {
            log.info("预算扣减: domain={}, -{} epsilon, 剩余={}", request.domain(), request.epsilon(), budget.remaining());
        }

        // 5. 扰动输出；任何异常都回滚扣减，且绝不返回未扰动原始值
        try {
            double noisyValue = perturb(request, summary);
            double remaining = session != null ? session.remaining() : budget.remaining();
            log.info("查询成功: domain={}, groupKey={}, epsilonConsumed={}, 剩余额度={}",
                    request.domain(), request.groupKey(), request.epsilon(), remaining);
            return QueryResult.ok(noisyValue, request.epsilon(), remaining);
        } catch (RuntimeException e) {
            if (session != null) {
                session.refund(request.epsilon());
            } else {
                budget.refund(request.epsilon());
            }
            double remaining = session != null ? session.remaining() : budget.remaining();
            log.warn("扰动阶段异常，扣减已回滚: domain={}, +{} epsilon, 剩余={}, 原因={}",
                    request.domain(), request.epsilon(), remaining, e.toString());
            return QueryResult.rejected(QueryStatus.REJECTED_INVALID_PARAMETER, remaining,
                    "内部处理异常，预算已回滚: " + e.getMessage());
        }
    }

    /** 用请求种子派生确定性噪声并加到聚合值上。 */
    private double perturb(QueryRequest request, GroupSummary summary) {
        long derivedSeed = request.seed()
                ^ Objects.hash(request.domain(), request.groupKey());
        double scale = LocalDataSource.MAX_VALUE / request.epsilon();
        return summary.sum() + new LaplaceMechanism(derivedSeed).sample(scale);
    }

    private QueryResult reject(QueryRequest request, QueryStatus status, double remaining, String reason) {
        log.info("查询拒绝: domain={}, groupKey={}, status={}, 剩余预算={}, 原因={}",
                request.domain(), request.groupKey(), status, remaining, reason);
        return QueryResult.rejected(status, remaining, reason);
    }

    /** 暴露账本只读视图，供测试与审计校验守恒性。 */
    public double remainingBudget(String domain) {
        PrivacyBudget budget = budgets.get(domain);
        return budget == null ? 0.0 : budget.remaining();
    }

    /** 单个数据域的只读审计视图（总额/已耗/被占/可用）；未知域返回 null。 */
    public BudgetAudit audit(String domain) {
        PrivacyBudget budget = budgets.get(domain);
        return budget == null ? null : budget.audit();
    }

    /** 所有数据域的只读审计视图。 */
    public List<BudgetAudit> auditAll() {
        return budgets.values().stream().map(PrivacyBudget::audit).toList();
    }
}
