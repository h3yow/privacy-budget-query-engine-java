package com.github.h3yow.privacybudgetqueryenginejava.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常处理：业务拒绝返回 422 与可区分原因码；错误体只含原因与说明，
 * 不包含任何原始聚合值或预算内部快照之外的敏感信息。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PrivacyQueryException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(PrivacyQueryException ex) {
        log.warn("[HTTP-REJECT] reason={} message={}", ex.getReason(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "rejected", true,
                "reason", ex.getReason().name(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("[HTTP-ERROR] 未预期异常（不返回任何数据）", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "rejected", true,
                "reason", "INTERNAL_ERROR",
                "message", "内部错误，请求未产生任何结果"
        ));
    }
}
