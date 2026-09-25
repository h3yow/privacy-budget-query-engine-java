package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.exception.GlobalExceptionHandler;
import com.github.h3yow.privacybudgetqueryenginejava.exception.PrivacyQueryException;
import com.github.h3yow.privacybudgetqueryenginejava.exception.RejectionReason;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationRequest;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationResponse;
import com.github.h3yow.privacybudgetqueryenginejava.model.BudgetSnapshot;
import com.github.h3yow.privacybudgetqueryenginejava.model.GroupResult;
import com.github.h3yow.privacybudgetqueryenginejava.service.PrivacyQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.web.QueryController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口测试（不使用 Mockito，避免本机 JDK25 与内置 ByteBuddy 的兼容问题）：
 * 成功体只含扰动值；拒绝路径返回 422 与可区分 reason，且不含数据字段。
 */
class QueryControllerTest {

    private MockMvc mvc(PrivacyQueryService service) {
        return MockMvcBuilders
                .standaloneSetup(new QueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 手写桩：构造参数全部为 null，仅覆写需要的方法。 */
    private PrivacyQueryService stubSuccess() {
        return new PrivacyQueryService(null, null, null, null) {
            @Override
            public AggregationResponse query(AggregationRequest request) {
                return new AggregationResponse("sales", 0.5, 2.5,
                        List.of(new GroupResult("A", 8.31, 2.0)));
            }

            @Override
            public BudgetSnapshot budget(String domain) {
                return new BudgetSnapshot(domain, 0.5, 3.0, 2.5);
            }
        };
    }

    private PrivacyQueryService stubReject() {
        return new PrivacyQueryService(null, null, null, null) {
            @Override
            public AggregationResponse query(AggregationRequest request) {
                throw new PrivacyQueryException(RejectionReason.BUDGET_EXHAUSTED, "预算不足");
            }
        };
    }

    private static final String BODY = """
            {
              "requesterId": "alice",
              "domain": "sales",
              "aggregationType": "COUNT",
              "groupColumn": "region",
              "sumColumn": null,
              "sensitivity": 1.0,
              "privacy": {"epsilon": 0.5, "delta": 0.0},
              "minGroupSize": 1,
              "seed": 42
            }
            """;

    @Test
    void success_returnsOnlyNoisyResult() throws Exception {
        mvc(stubSuccess())
                .perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("sales"))
                .andExpect(jsonPath("$.epsilonUsed").value(0.5))
                .andExpect(jsonPath("$.remainingEpsilon").value(2.5))
                .andExpect(jsonPath("$.results[0].groupKey").value("A"))
                .andExpect(jsonPath("$.results[0].value").value(8.31))
                .andExpect(jsonPath("$.results[0].noiseScale").value(2.0));
    }

    @Test
    void rejection_returns422WithDistinctReason_andNoData() throws Exception {
        mvc(stubReject())
                .perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejected").value(true))
                .andExpect(jsonPath("$.reason").value("BUDGET_EXHAUSTED"))
                .andExpect(jsonPath("$.message").value("预算不足"))
                .andExpect(jsonPath("$.results").doesNotExist());
    }
}
