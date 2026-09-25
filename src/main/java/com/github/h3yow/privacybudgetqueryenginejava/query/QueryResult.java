package com.github.h3yow.privacybudgetqueryenginejava.query;

/**
 * 查询结果：仅在 OK 时携带扰动后的值；任何拒绝路径都不含原始值。
 */
public record QueryResult(
        QueryStatus status,
        Double value,
        double epsilonConsumed,
        double budgetRemaining,
        String reason) {

    public static QueryResult ok(double value, double epsilonConsumed, double budgetRemaining) {
        return new QueryResult(QueryStatus.OK, value, epsilonConsumed, budgetRemaining, "OK");
    }

    public static QueryResult rejected(QueryStatus status, double budgetRemaining, String reason) {
        if (status == QueryStatus.OK) {
            throw new IllegalArgumentException("拒绝路径不允许使用 OK 状态");
        }
        // 拒绝路径：value 恒为 null，epsilonConsumed 恒为 0（预算未消耗或已回滚）
        return new QueryResult(status, null, 0.0, budgetRemaining, reason);
    }
}
