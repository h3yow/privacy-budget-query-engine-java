package com.github.h3yow.privacybudgetqueryenginejava.query;

/** 一次聚合查询请求。 */
public record QueryRequest(
        String domain,
        String groupKey,
        double epsilon,
        double delta,
        long minGroupSize,
        long seed) {
}
