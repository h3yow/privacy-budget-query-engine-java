package com.github.h3yow.privacybudgetqueryenginejava.model;

/**
 * 支持的聚合类型。
 * COUNT 使用拉普拉斯机制（灵敏度 1）；SUM 使用灵敏度由请求方声明的拉普拉斯机制。
 */
public enum AggregationType {
    COUNT,
    SUM
}
