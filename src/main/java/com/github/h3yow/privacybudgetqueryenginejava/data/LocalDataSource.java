package com.github.h3yow.privacybudgetqueryenginejava.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 本地模拟数据：多主体提交的记录，按 (domain, groupKey) 聚合。
 *
 * <p>数据由固定种子确定性地生成，保证测试与本地验证可复现。
 * 每条记录的取值被裁剪到 [0, {@link #MAX_VALUE}]，
 * 因此单主体对 sum 查询的最大贡献（灵敏度）为 {@link #MAX_VALUE}。
 */
public class LocalDataSource {

    /** 单条记录取值上界，即 sum 查询的 L1 灵敏度。 */
    public static final double MAX_VALUE = 100.0;

    private static final long DATA_SEED = 20260926L;

    /** domain -> groupKey -> 该分组内各主体提交的取值。 */
    private final Map<String, Map<String, List<Double>>> tables = new LinkedHashMap<>();

    public LocalDataSource() {
        // 两个数据域：各自独立的预算账本
        tables.put("health", buildDomain(1001, Map.of(
                "age-30-40", 64,
                "age-40-50", 48,
                "rare-cohort", 3)));   // 小分组：用于触发匿名度不足
        tables.put("finance", buildDomain(2002, Map.of(
                "income-mid", 80,
                "income-high", 32)));
    }

    private Map<String, List<Double>> buildDomain(long salt, Map<String, Integer> groupSizes) {
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        groupSizes.forEach((key, size) -> {
            Random r = new Random(DATA_SEED ^ salt ^ key.hashCode());
            List<Double> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(r.nextDouble() * MAX_VALUE);
            }
            groups.put(key, values);
        });
        return groups;
    }

    /** 返回某数据域下某分组的原始统计（仅服务端内部使用）。 */
    public GroupSummary summarize(String domain, String groupKey) {
        List<Double> values = tables.getOrDefault(domain, Map.of())
                .getOrDefault(groupKey, List.of());
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return new GroupSummary(values.size(), sum);
    }

    /** 返回某数据域下所有分组键。 */
    public List<String> groupKeys(String domain) {
        return List.copyOf(tables.getOrDefault(domain, Map.of()).keySet());
    }
}
