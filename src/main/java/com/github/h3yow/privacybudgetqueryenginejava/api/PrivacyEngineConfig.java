package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.Map;

/** 引擎装配：本地数据源 + 各数据域预算账本 + 会话管理器 + 查询服务。 */
@Configuration
@EnableScheduling
public class PrivacyEngineConfig {

    /** 各数据域的默认总预算（epsilon）。 */
    public static final double DEFAULT_DOMAIN_BUDGET = 5.0;

    /** 会话默认空闲超时：超过该时长未使用则自动回收并归还剩余额度。 */
    public static final long DEFAULT_SESSION_IDLE_TIMEOUT_MILLIS = 30_000L;

    @Bean
    public LocalDataSource localDataSource() {
        return new LocalDataSource();
    }

    @Bean
    public Map<String, PrivacyBudget> domainBudgets() {
        return Map.of(
                "health", new PrivacyBudget("health", DEFAULT_DOMAIN_BUDGET),
                "finance", new PrivacyBudget("finance", DEFAULT_DOMAIN_BUDGET));
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public SessionManager sessionManager(Map<String, PrivacyBudget> budgets, Clock clock) {
        return new SessionManager(budgets, clock, DEFAULT_SESSION_IDLE_TIMEOUT_MILLIS);
    }

    @Bean
    public AggregationQueryService aggregationQueryService(
            LocalDataSource dataSource, Map<String, PrivacyBudget> budgets, SessionManager sessionManager) {
        return new AggregationQueryService(dataSource, budgets, sessionManager);
    }
}
