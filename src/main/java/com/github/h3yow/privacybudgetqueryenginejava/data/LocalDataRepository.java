package com.github.h3yow.privacybudgetqueryenginejava.data;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 本地内存数据仓库：用确定性构造的数据模拟多主体可查询的数据集。
 *
 * <p>预置数据域与分组（内容在构造时固定，保证测试可复现）：
 * <ul>
 *   <li>{@code sales}：分组 A 8 行、B 3 行、C 12 行（用于匿名度边界测试）</li>
 *   <li>{@code hr}：单分组 X 5 行</li>
 * </ul>
 */
@Repository
public class LocalDataRepository {

    /** 已知数据域集合；查询未知域将被拒绝。 */
    public static final Set<String> KNOWN_DOMAINS = Set.of("sales", "hr");

    private final List<DataRow> rows = new ArrayList<>();

    public LocalDataRepository() {
        long id = 1;
        id = appendDomain(id, "sales", "A", 8, 100.0);
        id = appendDomain(id, "sales", "B", 3, 200.0);
        id = appendDomain(id, "sales", "C", 12, 50.0);
        id = appendDomain(id, "hr", "X", 5, 300.0);
    }

    private long appendDomain(long startId, String domain, String group, int count, double base) {
        long id = startId;
        for (int i = 0; i < count; i++) {
            // 确定性取值，避免任何随机源进入“原始数据”。
            double amount = base + (i % 5) * 10.0;
            rows.add(new DataRow(id++, domain, group, amount));
        }
        return id;
    }

    /**
     * 返回某数据域下的全部行（返回新列表，防止调用方篡改）。
     */
    public List<DataRow> findByDomain(String domain) {
        return rows.stream().filter(r -> r.domain().equals(domain)).toList();
    }

    /**
     * 数据域是否可被查询。测试可通过子类扩展。
     */
    public boolean knowsDomain(String domain) {
        return KNOWN_DOMAINS.contains(domain);
    }
}
