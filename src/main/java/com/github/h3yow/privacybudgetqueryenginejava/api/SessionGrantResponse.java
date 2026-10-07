package com.github.h3yow.privacybudgetqueryenginejava.api;

/**
 * 会话额度申请/结束响应。
 *
 * <p>{@code status} 为 "OK" 或 "REJECTED"；拒绝时 {@code sessionId} 为 null，
 * {@code reason} 说明原因（额度越界、超过可用预算、未知域、未知会话等）。
 */
public record SessionGrantResponse(
        String status,
        String sessionId,
        String domain,
        Double epsilonGranted,
        Double epsilonReturned,
        String reason) {

    public static SessionGrantResponse granted(String sessionId, String domain, double epsilonGranted) {
        return new SessionGrantResponse("OK", sessionId, domain, epsilonGranted, null, "OK");
    }

    public static SessionGrantResponse closed(String sessionId, String domain, double epsilonReturned) {
        return new SessionGrantResponse("OK", sessionId, domain, null, epsilonReturned, "OK");
    }

    public static SessionGrantResponse rejected(String reason) {
        return new SessionGrantResponse("REJECTED", null, null, null, null, reason);
    }
}
