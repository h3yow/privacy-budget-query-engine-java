package com.github.h3yow.privacybudgetqueryenginejava.exception;

/**
 * 请求被拒绝的可区分原因码。
 */
public enum RejectionReason {
    INVALID_EPSILON,
    INVALID_DELTA,
    INVALID_SENSITIVITY,
    INVALID_GROUP_SIZE,
    INVALID_DOMAIN,
    BUDGET_EXHAUSTED,
    ANONYMITY_TOO_SMALL
}
