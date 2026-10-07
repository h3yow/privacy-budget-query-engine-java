package com.github.h3yow.privacybudgetqueryenginejava.api;

/**
 * 会话额度申请请求。
 *
 * @param domain            数据域
 * @param epsilon           申请的额度（不超过该域当前可用预算）
 * @param idleTimeoutMillis 可选的空闲超时（毫秒）；null 表示使用服务端默认值
 */
public record SessionGrantRequest(String domain, double epsilon, Long idleTimeoutMillis) {
}
