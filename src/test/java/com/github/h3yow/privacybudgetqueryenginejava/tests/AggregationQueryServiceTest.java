package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryRequest;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryResult;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationQueryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AggregationQueryServiceTest.class);

    private AggregationQueryService newService(Map<String, PrivacyBudget> budgets) {
        return new AggregationQueryService(new LocalDataSource(), budgets);
    }

    private QueryRequest healthRequest(double epsilon, long seed) {
        return new QueryRequest("health", "age-30-40", epsilon, 1e-5, 5, seed);
    }

    @Test
    void sameSeedAndInputAreReproducible() {
        AggregationQueryService service = newService(Map.of(
                "health", new PrivacyBudget("health", 100.0)));
        QueryResult r1 = service.query(healthRequest(1.0, 42L));
        QueryResult r2 = service.query(healthRequest(1.0, 42L));
        log.info("可复现性校验: r1={}, r2={}", r1.value(), r2.value());
        assertEquals(QueryStatus.OK, r1.status());
        assertEquals(r1.value(), r2.value(), "相同种子与输入必须得到一致结果");
    }

    @Test
    void differentSeedsProduceDifferentNoise() {
        AggregationQueryService service = newService(Map.of(
                "health", new PrivacyBudget("health", 100.0)));
        QueryResult r1 = service.query(healthRequest(1.0, 1L));
        QueryResult r2 = service.query(healthRequest(1.0, 2L));
        log.info("差异校验: seed=1 -> {}, seed=2 -> {}", r1.value(), r2.value());
        assertNotEquals(r1.value(), r2.value(), "不同种子应产生差异");
    }

    @Test
    void invalidPrivacyParametersAreRejectedWithoutConsumingBudget() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        AggregationQueryService service = newService(Map.of("health", budget));
        double before = budget.remaining();

        List<QueryRequest> badRequests = List.of(
                healthRequest(0.0, 1L),
                healthRequest(-1.0, 1L),
                healthRequest(Double.NaN, 1L),
                healthRequest(100.0, 1L),                      // 超过单次 epsilon 上限
                new QueryRequest("health", "age-30-40", 1.0, -1e-4, 5, 1L), // delta 越界
                new QueryRequest("unknown", "age-30-40", 1.0, 1e-5, 5, 1L)); // 未知数据域

        for (QueryRequest req : badRequests) {
            QueryResult result = service.query(req);
            log.info("参数越界校验: epsilon={}, delta={}, status={}, reason={}",
                    req.epsilon(), req.delta(), result.status(), result.reason());
            assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, result.status());
            assertNull(result.value(), "拒绝路径不得返回值");
            assertEquals(0.0, result.epsilonConsumed(), "拒绝路径不得消耗预算");
        }
        assertEquals(before, budget.remaining(), "参数越界拒绝后预算必须守恒");
    }

    @Test
    void insufficientAnonymityIsRejectedWithoutConsumingBudget() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        AggregationQueryService service = newService(Map.of("health", budget));
        double before = budget.remaining();

        // rare-cohort 分组只有 3 条记录，最小匿名度要求 5
        QueryRequest req = new QueryRequest("health", "rare-cohort", 1.0, 1e-5, 5, 7L);
        QueryResult result = service.query(req);
        log.info("匿名度校验: status={}, reason={}, 预算 {} -> {}",
                result.status(), result.reason(), before, budget.remaining());

        assertEquals(QueryStatus.REJECTED_INSUFFICIENT_ANONYMITY, result.status());
        assertNull(result.value(), "匿名度不足不得返回任何值");
        assertEquals(before, budget.remaining(), "匿名度不足不得消耗预算");
    }

    @Test
    void budgetExhaustionIsRejectedAndDistinguishable() {
        PrivacyBudget budget = new PrivacyBudget("health", 2.5);
        AggregationQueryService service = newService(Map.of("health", budget));

        QueryResult ok1 = service.query(healthRequest(1.0, 1L));
        QueryResult ok2 = service.query(healthRequest(1.0, 2L));
        QueryResult denied = service.query(healthRequest(1.0, 3L)); // 仅剩 0.5，不足
        log.info("预算耗尽校验: ok1={}, ok2={}, denied={} ({}), 剩余={}",
                ok1.status(), ok2.status(), denied.status(), denied.reason(), budget.remaining());

        assertEquals(QueryStatus.OK, ok1.status());
        assertEquals(QueryStatus.OK, ok2.status());
        assertEquals(QueryStatus.REJECTED_BUDGET_EXHAUSTED, denied.status());
        assertNull(denied.value());
        assertEquals(0.5, budget.remaining(), 1e-9, "失败扣减不得发生，余额保持守恒");
    }

    @Test
    void concurrentRequestsNeverOverspendBudget() throws Exception {
        double total = 5.0;
        double perRequest = 1.0;
        int threads = 32;
        PrivacyBudget budget = new PrivacyBudget("health", total);
        AggregationQueryService service = newService(Map.of("health", budget));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<QueryResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long seed = i;
            tasks.add(() -> {
                start.await();
                return service.query(healthRequest(perRequest, seed));
            });
        }
        List<Future<QueryResult>> futures = new ArrayList<>();
        for (Callable<QueryResult> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();

        Map<QueryStatus, Integer> tally = new ConcurrentHashMap<>();
        double consumedSum = 0.0;
        for (Future<QueryResult> f : futures) {
            QueryResult r = f.get();
            tally.merge(r.status(), 1, Integer::sum);
            consumedSum += r.epsilonConsumed();
            if (r.status() != QueryStatus.OK) {
                assertNull(r.value(), "拒绝路径不得返回值");
            }
        }
        pool.shutdown();

        long successes = tally.getOrDefault(QueryStatus.OK, 0);
        log.info("并发校验: 线程数={}, 成功={}, 拒绝={}, 消耗合计={}, 账本剩余={}",
                threads, successes, tally.getOrDefault(QueryStatus.REJECTED_BUDGET_EXHAUSTED, 0),
                consumedSum, budget.remaining());

        assertEquals(5, successes, "预算 5.0 / 每次 1.0，恰好允许 5 次成功");
        assertEquals(threads - 5, tally.getOrDefault(QueryStatus.REJECTED_BUDGET_EXHAUSTED, 0));
        assertEquals(total, consumedSum + budget.remaining(), 1e-9,
                "消耗合计 + 剩余必须等于总预算（无重复扣减、无漏扣）");
        assertTrue(budget.remaining() >= 0.0, "并发下不得超支");
    }

    @Test
    void budgetConservationAcrossMixedOutcomes() {
        PrivacyBudget budget = new PrivacyBudget("health", 3.0);
        AggregationQueryService service = newService(Map.of("health", budget));

        QueryResult ok = service.query(healthRequest(1.0, 1L));
        QueryResult anon = service.query(new QueryRequest("health", "rare-cohort", 1.0, 1e-5, 5, 2L));
        QueryResult invalid = service.query(healthRequest(0.0, 3L));
        QueryResult exhausted = service.query(healthRequest(2.5, 4L)); // 剩 2.0，不足
        log.info("混合路径守恒校验: ok={}, anon={}, invalid={}, exhausted={}, 剩余={}",
                ok.status(), anon.status(), invalid.status(), exhausted.status(), budget.remaining());

        assertEquals(QueryStatus.OK, ok.status());
        assertEquals(QueryStatus.REJECTED_INSUFFICIENT_ANONYMITY, anon.status());
        assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, invalid.status());
        assertEquals(QueryStatus.REJECTED_BUDGET_EXHAUSTED, exhausted.status());
        assertEquals(2.0, budget.remaining(), 1e-9, "仅成功路径消耗预算，其余路径预算守恒");
    }
}
