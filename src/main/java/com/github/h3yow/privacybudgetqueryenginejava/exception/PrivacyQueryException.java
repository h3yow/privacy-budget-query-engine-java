package com.github.h3yow.privacybudgetqueryenginejava.exception;

/**
 * 所有业务拒绝/失败的统一异常。携带原因码，保证入口层能返回可区分的错误。
 */
public class PrivacyQueryException extends RuntimeException {

    private final RejectionReason reason;

    public PrivacyQueryException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
