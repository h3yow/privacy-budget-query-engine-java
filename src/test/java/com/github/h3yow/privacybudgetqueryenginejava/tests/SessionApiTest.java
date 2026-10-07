package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话额度与预算审计的 REST 集成测试。
 *
 * <p>注意：与 {@code QueryControllerTest} 共享 Spring 上下文（账本有状态），
 * 因此本类只使用 finance 域，避免跨类状态干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionApiTest {

    private static final Logger log = LoggerFactory.getLogger(SessionApiTest.class);

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sessionLifecycleOverRest() throws Exception {
        // 1. 申请会话额度
        MvcResult granted = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"finance\",\"epsilon\":3.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.epsilonGranted").value(3.0))
                .andReturn();
        String sessionId = mapper.readTree(granted.getResponse().getContentAsString())
                .get("sessionId").asText();
        log.info("REST 申请会话: {}", granted.getResponse().getContentAsString());

        // 2. 审计：3.0 被占着但还没花
        MvcResult audit1 = mockMvc.perform(get("/api/budgets/finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5.0))
                .andExpect(jsonPath("$.consumed").value(0.0))
                .andExpect(jsonPath("$.reserved").value(3.0))
                .andExpect(jsonPath("$.available").value(2.0))
                .andReturn();
        log.info("REST 审计（申请后）: {}", audit1.getResponse().getContentAsString());

        // 3. 用会话查询：从会话额度内扣
        String queryBody = """
                {"domain":"finance","groupKey":"income-mid","epsilon":1.0,"delta":1e-5,
                 "minGroupSize":5,"seed":42,"sessionId":"%s"}
                """.formatted(sessionId);
        mockMvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content(queryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.value").isNumber())
                .andExpect(jsonPath("$.budgetRemaining").value(2.0));

        // 4. 审计：1.0 已花掉，2.0 仍被占着 —— 花掉的与被占的可分辨
        MvcResult audit2 = mockMvc.perform(get("/api/budgets/finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(1.0))
                .andExpect(jsonPath("$.reserved").value(2.0))
                .andExpect(jsonPath("$.available").value(2.0))
                .andReturn();
        log.info("REST 审计（消费后）: {}", audit2.getResponse().getContentAsString());

        // 5. 主动结束：归还剩余 2.0
        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.epsilonReturned").value(2.0));

        // 6. 已结束的会话再查询：明确拒绝，无任何结果值
        MvcResult denied = mockMvc.perform(
                        post("/api/query").contentType(MediaType.APPLICATION_JSON).content(queryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_SESSION_INVALID"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.epsilonConsumed").value(0.0))
                .andReturn();
        log.info("REST 已结束会话查询: {}", denied.getResponse().getContentAsString());

        // 7. 审计守恒：已花 + 被占 + 可用 == 总额
        MvcResult audit3 = mockMvc.perform(get("/api/budgets/finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(1.0))
                .andExpect(jsonPath("$.reserved").value(0.0))
                .andExpect(jsonPath("$.available").value(4.0))
                .andReturn();
        JsonNode a3 = mapper.readTree(audit3.getResponse().getContentAsString());
        log.info("REST 审计（结束后）: {}", a3);
        assertEquals(a3.get("total").asDouble(),
                a3.get("consumed").asDouble() + a3.get("reserved").asDouble()
                        + a3.get("available").asDouble(), 1e-9, "审计数字守恒");

        // 8. 兼容性：老调用方不带 sessionId 的请求方式与返回结构不变
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"finance\",\"groupKey\":\"income-mid\",\"epsilon\":1.0,"
                                + "\"delta\":1e-5,\"minGroupSize\":5,\"seed\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.value").isNumber())
                .andExpect(jsonPath("$.epsilonConsumed").value(1.0));
    }

    @Test
    void overGrantAndUnknownSessionAreRejected() throws Exception {
        // 申请超过可用预算
        MvcResult over = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"finance\",\"epsilon\":100.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andReturn();
        log.info("REST 超额申请: {}", over.getResponse().getContentAsString());

        // 不存在的会话用于查询
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"finance\",\"groupKey\":\"income-mid\",\"epsilon\":1.0,"
                                + "\"delta\":1e-5,\"minGroupSize\":5,\"seed\":1,"
                                + "\"sessionId\":\"no-such-session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_SESSION_INVALID"))
                .andExpect(jsonPath("$.value").doesNotExist());

        // 结束不存在的会话
        mockMvc.perform(delete("/api/sessions/no-such-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 未知数据域审计返回 404
        mockMvc.perform(get("/api/budgets/unknown-domain"))
                .andExpect(status().isNotFound());
    }
}
