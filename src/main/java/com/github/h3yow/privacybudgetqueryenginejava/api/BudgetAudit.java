package com.github.h3yow.privacybudgetqueryenginejava.api;

/** 数据域预算只读审计视图：consumed + reserved + available == total 恒成立。 */
public record BudgetAudit(
        String domain,
        double total,
        double consumed,
        double reserved,
        double available) {
}
