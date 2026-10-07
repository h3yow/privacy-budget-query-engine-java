package com.github.h3yow.privacybudgetqueryenginejava.privacy;

/**
 * 单个数据域预算账本的只读审计视图。
 *
 * <p>守恒不变式：{@code consumed + reserved + available == total}。
 * <ul>
 *   <li>consumed：已经真正花掉的预算；</li>
 *   <li>reserved：被会话额度占着但还没花的预算；</li>
 *   <li>available：还能用（可被新查询或新会话申请）的预算。</li>
 * </ul>
 */
public record BudgetAudit(String domain, double total, double consumed, double reserved, double available) {
}
