package com.github.h3yow.privacybudgetqueryenginejava.query;

/** 查询判定结果：成功或各类可区分的拒绝原因。 */
public enum QueryStatus {
    OK,
    REJECTED_INVALID_PARAMETER,
    REJECTED_BUDGET_EXHAUSTED,
    REJECTED_INSUFFICIENT_ANONYMITY
}
