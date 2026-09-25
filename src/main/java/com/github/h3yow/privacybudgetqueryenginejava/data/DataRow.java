package com.github.h3yow.privacybudgetqueryenginejava.data;

/**
 * 本地模拟数据的一行记录。
 *
 * @param id     记录主键
 * @param domain 所属数据域
 * @param group  分组维度值
 * @param amount SUM 聚合取值
 */
public record DataRow(long id, String domain, String group, double amount) {
}
