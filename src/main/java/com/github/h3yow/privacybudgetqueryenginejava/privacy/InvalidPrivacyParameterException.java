package com.github.h3yow.privacybudgetqueryenginejava.privacy;

/** 隐私参数越界（epsilon/delta 不在允许范围内）。 */
public class InvalidPrivacyParameterException extends RuntimeException {
    public InvalidPrivacyParameterException(String message) {
        super(message);
    }
}
