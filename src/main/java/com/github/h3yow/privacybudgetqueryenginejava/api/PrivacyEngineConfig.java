package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudgetManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/** 引擎装配：本地数据源 + 各数据域预算账本 + 会话额度管理 + 查询服务。 */
@Configuration
@EnableScheduling
public class PrivacyEngineConfig {

    /** 各数据域的默认总预算（epsilon）。 */
    public static final double DEFAULT_DOMAIN_BUDGET = 5.0;

    @Bean
    public LocalDataSource localDataSource() {
        return new LocalDataSource();
    }

    @Bean
    public Map<String, PrivacyBudget> privacyBudgets() {
        return Map.of(
                "health", new PrivacyBudget("health", DEFAULT_DOMAIN_BUDGET),
                "finance", new PrivacyBudget("finance", DEFAULT_DOMAIN_BUDGET));
    }

    @Bean
    public SessionBudgetManager sessionBudgetManager(Map<String, PrivacyBudget> budgets) {
        return new SessionBudgetManager(budgets);
    }

    @Bean
    public AggregationQueryService aggregationQueryService(LocalDataSource dataSource,
                                                           Map<String, PrivacyBudget> budgets,
                                                           SessionBudgetManager sessionManager) {
        return new AggregationQueryService(dataSource, budgets, sessionManager);
    }
}
