package com.github.h3yow.privacybudgetqueryenginejava.model;

/**
 * 某个数据域在某一时刻的预算账户快照，用于审计与可解释性。
 *
 * @param domain   数据域
 * @param spent    已消耗 epsilon（累计，回滚的消耗不计入）
 * @param limit    预算上界
 * @param remaining 剩余 epsilon
 */
public record BudgetSnapshot(String domain, double spent, double limit, double remaining) {
}
