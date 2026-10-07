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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 会话额度 REST 集成测试：申请 → 会话查询 → 审计 → 结束 → 失效拒绝。 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SessionControllerTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sessionLifecycleOverRestEntry() throws Exception {
        // 1. 申请会话额度（finance 域总额 5.0）
        MvcResult created = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"finance\",\"epsilon\":3.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.sessionId").isString())
                .andExpect(jsonPath("$.session.granted").value(3.0))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(body, "$.sessionId");
        log.info("REST 会话申请响应: {}", body);

        // 2. 审计：3.0 处于占用态，尚未实耗
        MvcResult audit1 = mockMvc.perform(get("/api/budget/finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5.0))
                .andExpect(jsonPath("$.consumed").value(0.0))
                .andExpect(jsonPath("$.reserved").value(3.0))
                .andExpect(jsonPath("$.available").value(2.0))
                .andReturn();
        log.info("REST 审计(申请后): {}", audit1.getResponse().getContentAsString());

        // 3. 带会话查询：从会话额度扣费
        MvcResult queried = mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"domain":"finance","groupKey":"income-mid","epsilon":1.0,
                                 "delta":1e-5,"minGroupSize":5,"seed":42,"sessionId":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.value").isNumber())
                .andExpect(jsonPath("$.budgetRemaining").value(2.0))
                .andReturn();
        log.info("REST 会话查询响应: {}", queried.getResponse().getContentAsString());

        // 4. 审计：1.0 转为实耗，占用降为 2.0，守恒成立
        mockMvc.perform(get("/api/budget/finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(1.0))
                .andExpect(jsonPath("$.reserved").value(2.0))
                .andExpect(jsonPath("$.available").value(2.0));

        // 5. 主动结束：未用的 2.0 归还
        MvcResult closed = mockMvc.perform(delete("/api/session/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.consumed").value(1.0))
                .andExpect(jsonPath("$.held").value(2.0))
                .andReturn();
        log.info("REST 会话结束响应: {}", closed.getResponse().getContentAsString());

        MvcResult audit2 = mockMvc.perform(get("/api/budget/finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(1.0))
                .andExpect(jsonPath("$.reserved").value(0.0))
                .andExpect(jsonPath("$.available").value(4.0))
                .andReturn();
        log.info("REST 审计(结束后): {}", audit2.getResponse().getContentAsString());

        // 6. 已结束的会话再查询：可区分的失效拒绝，不返回任何结果值
        MvcResult denied = mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"domain":"finance","groupKey":"income-mid","epsilon":1.0,
                                 "delta":1e-5,"minGroupSize":5,"seed":43,"sessionId":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_SESSION_INVALID"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.epsilonConsumed").value(0.0))
                .andReturn();
        log.info("REST 失效会话查询响应: {}", denied.getResponse().getContentAsString());
    }

    @Test
    void sessionCreationValidationOverRestEntry() throws Exception {
        // 9.0 是合法 epsilon，但超过 finance 域总额 5.0，必须按预算不足拒绝
        MvcResult overdraw = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"finance\",\"epsilon\":9.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_BUDGET_EXHAUSTED"))
                .andReturn();
        log.info("REST 超额申请响应: {}", overdraw.getResponse().getContentAsString());

        MvcResult badParam = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"health\",\"epsilon\":-1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_INVALID_PARAMETER"))
                .andReturn();
        log.info("REST 非法申请响应: {}", badParam.getResponse().getContentAsString());
    }

    @Test
    void unknownDomainAuditIs404() throws Exception {
        mockMvc.perform(get("/api/budget/unknown"))
                .andExpect(status().isNotFound());
    }
}
