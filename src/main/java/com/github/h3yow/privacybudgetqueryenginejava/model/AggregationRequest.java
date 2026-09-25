package com.github.h3yow.privacybudgetqueryenginejava.model;

/**
 * 一次差分隐私聚合请求（由多主体之一提交）。
 *
 * @param requesterId     提交主体标识，仅用于日志与审计
 * @param domain          数据域标识；同一数据域共享同一个隐私预算账户
 * @param aggregationType 聚合方式
 * @param groupColumn     分组列名，空串表示不分组（全域聚合）
 * @param sumColumn       SUM 聚合时的取值列；COUNT 时忽略
 * @param sensitivity     SUM 的 L1 灵敏度上界（单条记录的最大贡献绝对值）；COUNT 固定为 1
 * @param privacy         声明的隐私参数
 * @param minGroupSize    最小匿名度：每个分组的原始记录数下限
 * @param seed            可复现扰动种子
 */
public record AggregationRequest(
        String requesterId,
        String domain,
        AggregationType aggregationType,
        String groupColumn,
        String sumColumn,
        double sensitivity,
        PrivacyParams privacy,
        int minGroupSize,
        long seed
) {
}
