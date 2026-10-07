package com.github.h3yow.privacybudgetqueryenginejava.session;

/** 会话额度的只读快照：能区分"已花掉"(consumed)与"仍被占着"(held)。 */
public record SessionInfo(
        String sessionId,
        String domain,
        SessionState state,
        double granted,
        double consumed,
        double held,
        long expiresAtEpochMillis) {
}
