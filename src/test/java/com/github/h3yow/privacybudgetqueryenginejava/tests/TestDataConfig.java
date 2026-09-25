package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.data.DataRow;
import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试数据配置：在主仓库数据（sales/hr）之外支持动态注册隔离数据域，
 * 每个用例使用独立域名即可获得独立预算账户，互不干扰、可重复运行。
 */
@TestConfiguration
public class TestDataConfig {

    /** 域名 -> 每个分组的行数（分组名固定 G1, G2, ...）。 */
    private static final Map<String, List<Integer>> DOMAIN_GROUPS = new ConcurrentHashMap<>();
    private static final AtomicLong ID_SEQ = new AtomicLong(5_000_000L);

    /**
     * 注册一个测试数据域。
     *
     * @param domain     域名
     * @param groupSizes 每个分组的行数；例如 List.of(3, 8) 表示两个分组
     */
    public static void registerDomain(String domain, List<Integer> groupSizes) {
        DOMAIN_GROUPS.put(domain, new ArrayList<>(groupSizes));
    }

    @Bean
    @Primary
    public LocalDataRepository testLocalDataRepository() {
        return new LocalDataRepository() {
            @Override
            public List<DataRow> findByDomain(String domain) {
                List<Integer> groups = DOMAIN_GROUPS.get(domain);
                if (groups != null) {
                    return buildRows(domain, groups);
                }
                return super.findByDomain(domain);
            }

            @Override
            public boolean knowsDomain(String domain) {
                return DOMAIN_GROUPS.containsKey(domain) || super.knowsDomain(domain);
            }
        };
    }

    private static List<DataRow> buildRows(String domain, List<Integer> groupSizes) {
        List<DataRow> rows = new ArrayList<>();
        for (int g = 0; g < groupSizes.size(); g++) {
            String group = "G" + (g + 1);
            int n = groupSizes.get(g);
            for (int i = 0; i < n; i++) {
                rows.add(new DataRow(ID_SEQ.incrementAndGet(), domain, group, 10.0 * (g + 1) + i));
            }
        }
        return rows;
    }
}
