package com.github.h3yow.privacybudgetqueryenginejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 入口集成测试：验证各入口路径的判定与预算扣减。 */
@SpringBootTest
@AutoConfigureMockMvc
class QueryControllerTest {

    private static final Logger log = LoggerFactory.getLogger(QueryControllerTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void okThenExhaustedOverRestEntry() throws Exception {
        // health 域默认预算 5.0，每次请求 epsilon=2.0：第 3 次应被拒绝
        for (int i = 1; i <= 2; i++) {
            MvcResult result = postQuery("health", "age-30-40", 2.0, i)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OK"))
                    .andExpect(jsonPath("$.value").isNumber())
                    .andReturn();
            log.info("REST 第 {} 次请求响应: {}", i, result.getResponse().getContentAsString());
        }
        MvcResult denied = postQuery("health", "age-30-40", 2.0, 99)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_BUDGET_EXHAUSTED"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andReturn();
        log.info("REST 第 3 次请求（预算不足）响应: {}", denied.getResponse().getContentAsString());
    }

    @Test
    void invalidParameterOverRestEntry() throws Exception {
        MvcResult result = postQuery("finance", "income-mid", -1.0, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_INVALID_PARAMETER"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andReturn();
        log.info("REST 参数越界响应: {}", result.getResponse().getContentAsString());
    }

    @Test
    void insufficientAnonymityOverRestEntry() throws Exception {
        MvcResult result = postQuery("health", "rare-cohort", 1.0, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_INSUFFICIENT_ANONYMITY"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andReturn();
        log.info("REST 匿名度不足响应: {}", result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postQuery(
            String domain, String groupKey, double epsilon, long seed) throws Exception {
        String body = """
                {"domain":"%s","groupKey":"%s","epsilon":%s,"delta":1e-5,"minGroupSize":5,"seed":%d}
                """.formatted(domain, groupKey, epsilon, seed);
        return mockMvc.perform(post("/api/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
