package com.github.h3yow.privacybudgetqueryenginejava.query;

/**
 * 一次聚合查询请求。
 *
 * <p>{@code sessionId} 可空：为空时走原来的"单次扣预算"路径（老调用方兼容）；
 * 非空时从对应会话额度中扣费。
 */
public record QueryRequest(
        String domain,
        String groupKey,
        double epsilon,
        double delta,
        long minGroupSize,
        long seed,
        String sessionId) {

    /** 兼容老调用方：不带会话的请求。 */
    public QueryRequest(String domain, String groupKey, double epsilon, double delta,
                        long minGroupSize, long seed) {
        this(domain, groupKey, epsilon, delta, minGroupSize, seed, null);
    }
}
