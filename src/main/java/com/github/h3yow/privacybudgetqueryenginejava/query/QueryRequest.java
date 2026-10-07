package com.github.h3yow.privacybudgetqueryenginejava.query;

/**
 * 一次聚合查询请求。
 *
 * <p>{@code sessionId} 可选：为 null 时按老方式从数据域账本直接扣减；
 * 非 null 时从对应会话额度中扣减。老调用方不传该字段即可，行为完全不变。
 */
public record QueryRequest(
        String domain,
        String groupKey,
        double epsilon,
        double delta,
        long minGroupSize,
        long seed,
        String sessionId) {

    /** 兼容老调用方：不带会话额度的请求。 */
    public QueryRequest(String domain, String groupKey, double epsilon, double delta,
                        long minGroupSize, long seed) {
        this(domain, groupKey, epsilon, delta, minGroupSize, seed, null);
    }
}
