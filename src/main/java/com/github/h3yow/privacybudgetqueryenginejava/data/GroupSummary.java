package com.github.h3yow.privacybudgetqueryenginejava.data;

/** 某个分组在本地数据中的原始统计（仅服务端可见，绝不直接外发）。 */
public record GroupSummary(long count, double sum) {
}
