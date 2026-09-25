package com.github.h3yow.privacybudgetqueryenginejava.model;

import java.util.Map;

/**
 * 单个分组的扰动结果。任何路径下都不会暴露原始聚合值。
 *
 * @param groupKey 分组键；全域聚合时为 "*"
 * @param value    加噪后的聚合值（按 scale 四舍五入）
 * @param noiseScale 本次使用的拉普拉斯噪声尺度，用于结果可解释性
 */
public record GroupResult(String groupKey, double value, double noiseScale) {

    public Map<String, Object> toLogMap() {
        return Map.of("group", groupKey, "noisyValue", value, "noiseScale", noiseScale);
    }
}
