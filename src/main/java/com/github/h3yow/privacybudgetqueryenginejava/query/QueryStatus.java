package com.github.h3yow.privacybudgetqueryenginejava.query;

/** 查询判定结果：成功或各类可区分的拒绝原因。 */
public enum QueryStatus {
    OK,
    REJECTED_INVALID_PARAMETER,
    REJECTED_BUDGET_EXHAUSTED,
    REJECTED_INSUFFICIENT_ANONYMITY,
    /** 会话额度不存在、已结束或已过期；与参数越界、匿名度不足可区分。 */
    REJECTED_SESSION_INVALID
}
