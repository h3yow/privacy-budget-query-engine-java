package com.github.h3yow.privacybudgetqueryenginejava.model;

import java.util.List;

/**
 * 成功请求的返回体：只包含扰动结果与可解释性元数据，绝不包含原始聚合值。
 *
 * @param domain       数据域
 * @param epsilonUsed  本次实际消耗的 epsilon
 * @param remainingEpsilon 该数据域剩余 epsilon
 * @param results      各分组扰动结果
 */
public record AggregationResponse(
        String domain,
        double epsilonUsed,
        double remainingEpsilon,
        List<GroupResult> results
) {
}
