package com.github.h3yow.privacybudgetqueryenginejava.api;

/** 会话额度申请：idleTimeoutMillis 缺省时使用服务端默认值。 */
public record CreateSessionRequest(
        String domain,
        double epsilon,
        Long idleTimeoutMillis) {
}
