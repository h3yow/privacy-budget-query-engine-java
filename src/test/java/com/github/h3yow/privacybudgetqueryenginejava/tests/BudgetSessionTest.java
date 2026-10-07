package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryRequest;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryResult;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryStatus;
import com.github.h3yow.privacybudgetqueryenginejava.session.CreateSessionResponse;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionManager;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话额度：申请、并发消费、生命周期回收、审计守恒与失效拒绝语义。 */
class BudgetSessionTest {

    private static final Logger log = LoggerFactory.getLogger(BudgetSessionTest.class);
    private static final long IDLE_TIMEOUT = 10_000L;

    private record Fixture(PrivacyBudget budget, SessionManager sessions, AggregationQueryService service,
                           MutableClock clock) {
    }

    private Fixture newFixture(double domainBudget) {
        PrivacyBudget budget = new PrivacyBudget("health", domainBudget);
        MutableClock clock = new MutableClock(1_000_000L);
        SessionManager sessions = new SessionManager(Map.of("health", budget), clock, IDLE_TIMEOUT);
        AggregationQueryService service = new AggregationQueryService(
                new LocalDataSource(), Map.of("health", budget), sessions);
        return new Fixture(budget, sessions, service, clock);
    }

    private QueryRequest sessionRequest(String sessionId, double epsilon, long seed) {
        return new QueryRequest("health", "age-30-40", epsilon, 1e-5, 5, seed, sessionId);
    }

    private void assertConservation(PrivacyBudget budget, double total) {
        assertEquals(total, budget.consumed() + budget.reserved() + budget.available(), 1e-9,
                "审计守恒: 实耗 + 占用 + 可用 必须等于总额");
    }

    @Test
    void createReservesBudgetAndValidatesRequest() {
        Fixture f = newFixture(5.0);

        CreateSessionResponse ok = f.sessions().create("health", 2.0, null);
        log.info("会话申请: status={}, sessionId={}, 域占用={}, 域可用={}",
                ok.status(), ok.sessionId(), f.budget().reserved(), f.budget().available());
        assertEquals(QueryStatus.OK, ok.status());
        assertEquals(2.0, f.budget().reserved(), 1e-9, "申请后额度处于占用态而非实耗");
        assertEquals(0.0, f.budget().consumed(), 1e-9);
        assertEquals(3.0, f.budget().available(), 1e-9);

        CreateSessionResponse overdraw = f.sessions().create("health", 4.0, null);
        log.info("超额申请: status={}, reason={}", overdraw.status(), overdraw.reason());
        assertEquals(QueryStatus.REJECTED_BUDGET_EXHAUSTED, overdraw.status(),
                "申请量超过域当前可用预算必须被拒绝");
        assertEquals(2.0, f.budget().reserved(), 1e-9, "拒绝后占用不变");

        for (CreateSessionResponse bad : List.of(
                f.sessions().create("health", 0.0, null),
                f.sessions().create("health", -1.0, null),
                f.sessions().create("health", Double.NaN, null),
                f.sessions().create("unknown", 1.0, null),
                f.sessions().create("health", 1.0, 0L))) {
            log.info("非法申请: status={}, reason={}", bad.status(), bad.reason());
            assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, bad.status());
        }
        assertConservation(f.budget(), 5.0);
    }

    @Test
    void concurrentSessionConsumptionNeverOverspends() throws Exception {
        Fixture f = newFixture(100.0);
        CreateSessionResponse created = f.sessions().create("health", 5.0, null);
        String sessionId = created.sessionId();

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<QueryResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long seed = i;
            tasks.add(() -> {
                start.await();
                return f.service().query(sessionRequest(sessionId, 1.0, seed));
            });
        }
        List<Future<QueryResult>> futures = new ArrayList<>();
        for (Callable<QueryResult> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();

        Map<QueryStatus, Integer> tally = new ConcurrentHashMap<>();
        double consumedSum = 0.0;
        for (Future<QueryResult> future : futures) {
            QueryResult r = future.get();
            tally.merge(r.status(), 1, Integer::sum);
            consumedSum += r.epsilonConsumed();
            if (r.status() != QueryStatus.OK) {
                assertNull(r.value(), "拒绝路径不得返回值");
            }
        }
        pool.shutdown();

        long successes = tally.getOrDefault(QueryStatus.OK, 0);
        log.info("会话并发消费: 线程数={}, 成功={}, 拒绝={}, 消耗合计={}, 会话剩余={}, 域实耗={}, 域占用={}",
                threads, successes, tally.getOrDefault(QueryStatus.REJECTED_BUDGET_EXHAUSTED, 0),
                consumedSum, f.sessions().info(sessionId).held(),
                f.budget().consumed(), f.budget().reserved());

        assertEquals(5, successes, "会话额度 5.0 / 每次 1.0，恰好允许 5 次成功");
        assertEquals(threads - 5, tally.getOrDefault(QueryStatus.REJECTED_BUDGET_EXHAUSTED, 0));
        assertEquals(5.0, f.budget().consumed(), 1e-9, "会话消费应实时转为域实耗");
        assertEquals(0.0, f.budget().reserved(), 1e-9, "额度用完后占用清零");
        assertConservation(f.budget(), 100.0);
    }

    @Test
    void idleSessionIsReapedAndBudgetImmediatelyReusable() {
        Fixture f = newFixture(5.0);
        CreateSessionResponse created = f.sessions().create("health", 4.0, null);
        String sessionId = created.sessionId();
        assertEquals(1.0, f.budget().available(), 1e-9);

        // 花掉一部分，再闲置至超时
        QueryResult ok = f.service().query(sessionRequest(sessionId, 1.5, 1L));
        assertEquals(QueryStatus.OK, ok.status());
        f.clock().advance(IDLE_TIMEOUT + 1);

        int reaped = f.sessions().reapExpired();
        log.info("空闲回收: reaped={}, 会话状态={}, 域实耗={}, 域占用={}, 域可用={}",
                reaped, f.sessions().info(sessionId).state(),
                f.budget().consumed(), f.budget().reserved(), f.budget().available());

        assertEquals(1, reaped);
        assertEquals(SessionState.EXPIRED, f.sessions().info(sessionId).state());
        assertEquals(1.5, f.budget().consumed(), 1e-9, "已花掉的部分保持实耗");
        assertEquals(0.0, f.budget().reserved(), 1e-9, "未用部分已归还，占用清零");
        assertEquals(3.5, f.budget().available(), 1e-9, "归还后立即可用");

        // 同一数据域的其他请求马上能用上归还的额度
        CreateSessionResponse next = f.sessions().create("health", 3.5, null);
        assertEquals(QueryStatus.OK, next.status(), "归还后的额度必须能被新会话立即申请");

        // 重复回收不得重复归还
        assertEquals(0, f.sessions().reapExpired());
        assertEquals(1.5, f.budget().consumed(), 1e-9);
        assertConservation(f.budget(), 5.0);
    }

    @Test
    void lazyExpiryOnAccessAlsoReleasesBudget() {
        Fixture f = newFixture(5.0);
        String sessionId = f.sessions().create("health", 4.0, null).sessionId();
        f.clock().advance(IDLE_TIMEOUT + 1);

        // 不跑定时回收，直接用过期会话查询：应惰性回收并按会话失效拒绝
        QueryResult r = f.service().query(sessionRequest(sessionId, 1.0, 1L));
        log.info("惰性过期: status={}, reason={}, 域可用={}", r.status(), r.reason(), f.budget().available());
        assertEquals(QueryStatus.REJECTED_SESSION_INVALID, r.status());
        assertNull(r.value());
        assertEquals(5.0, f.budget().available(), 1e-9, "惰性过期同样要归还全部未用额度");
        assertConservation(f.budget(), 5.0);
    }

    @Test
    void closeReturnsUnusedBudgetAndIsIdempotent() {
        Fixture f = newFixture(5.0);
        String sessionId = f.sessions().create("health", 3.0, null).sessionId();
        f.service().query(sessionRequest(sessionId, 1.0, 1L));

        assertTrue(f.sessions().close(sessionId));
        log.info("主动结束: 状态={}, 域实耗={}, 域占用={}, 域可用={}",
                f.sessions().info(sessionId).state(),
                f.budget().consumed(), f.budget().reserved(), f.budget().available());
        assertEquals(SessionState.CLOSED, f.sessions().info(sessionId).state());
        assertEquals(1.0, f.budget().consumed(), 1e-9);
        assertEquals(0.0, f.budget().reserved(), 1e-9);
        assertEquals(4.0, f.budget().available(), 1e-9);

        // 幂等：重复关闭不得再次归还
        assertFalse(f.sessions().close(sessionId));
        assertEquals(4.0, f.budget().available(), 1e-9, "重复关闭不得重复归还");
        assertConservation(f.budget(), 5.0);
    }

    @Test
    void invalidSessionQueriesAreRejectedDistinguishably() {
        Fixture f = newFixture(5.0);
        String sessionId = f.sessions().create("health", 2.0, null).sessionId();
        f.sessions().close(sessionId);

        List<QueryRequest> badSessionRequests = List.of(
                sessionRequest("no-such-session", 1.0, 1L),   // 不存在
                sessionRequest(sessionId, 1.0, 2L));          // 已结束
        for (QueryRequest req : badSessionRequests) {
            QueryResult r = f.service().query(req);
            log.info("失效会话查询: sessionId={}, status={}, reason={}", req.sessionId(), r.status(), r.reason());
            assertEquals(QueryStatus.REJECTED_SESSION_INVALID, r.status(),
                    "失效会话必须可区分地拒绝，不能当成正常查询");
            assertNull(r.value(), "失效会话不得返回任何原始或扰动结果");
            assertEquals(0.0, r.epsilonConsumed(), "失效会话不得消耗预算");
        }
        // 与参数越界是可区分的不同状态
        QueryResult invalidParam = f.service().query(sessionRequest(sessionId, 1.0, 3L));
        assertEquals(QueryStatus.REJECTED_SESSION_INVALID, invalidParam.status());
        QueryResult badParam = f.service().query(
                new QueryRequest("health", "age-30-40", -1.0, 1e-5, 5, 4L));
        assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, badParam.status());
        assertConservation(f.budget(), 5.0);
    }

    @Test
    void perturbFailureRefundsBothDirectAndSessionPaths() {
        Fixture f = newFixture(5.0);
        String sessionId = f.sessions().create("health", 2.0, null).sessionId();

        // epsilon 为极小正数：参数校验通过，但噪声尺度 100/epsilon = Infinity 触发扰动异常
        double tinyEpsilon = 1e-320;
        QueryResult sessionPath = f.service().query(sessionRequest(sessionId, tinyEpsilon, 1L));
        log.info("会话路径扰动异常: status={}, reason={}, 会话剩余={}",
                sessionPath.status(), sessionPath.reason(), f.sessions().info(sessionId).held());
        assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, sessionPath.status());
        assertNull(sessionPath.value());
        assertEquals(2.0, f.sessions().info(sessionId).held(), 1e-9, "会话额度必须完整回滚");
        assertEquals(0.0, f.budget().consumed(), 1e-9);
        assertEquals(2.0, f.budget().reserved(), 1e-9);

        QueryResult directPath = f.service().query(
                new QueryRequest("health", "age-30-40", tinyEpsilon, 1e-5, 5, 2L));
        log.info("直接路径扰动异常: status={}, reason={}", directPath.status(), directPath.reason());
        assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, directPath.status());
        assertEquals(0.0, f.budget().consumed(), 1e-9, "直接扣减也必须回滚");
        assertConservation(f.budget(), 5.0);
    }

    @Test
    void auditConservationAcrossMixedPaths() {
        Fixture f = newFixture(10.0);
        // 直接查询花掉 1.0
        f.service().query(new QueryRequest("health", "age-30-40", 1.0, 1e-5, 5, 1L));
        // 会话 A：申请 3.0，花 1.0，主动结束
        String a = f.sessions().create("health", 3.0, null).sessionId();
        f.service().query(sessionRequest(a, 1.0, 2L));
        f.sessions().close(a);
        // 会话 B：申请 2.0，闲置过期
        f.sessions().create("health", 2.0, null);
        f.clock().advance(IDLE_TIMEOUT + 1);
        f.sessions().reapExpired();
        // 会话 C：申请 4.0，花 0.5，仍持有 3.5
        String c = f.sessions().create("health", 4.0, null).sessionId();
        f.service().query(sessionRequest(c, 0.5, 3L));

        log.info("审计守恒: total=10.0, 实耗={}, 占用={}, 可用={}",
                f.budget().consumed(), f.budget().reserved(), f.budget().available());
        assertEquals(2.5, f.budget().consumed(), 1e-9, "1.0 直接 + 1.0 会话A + 0.5 会话C");
        assertEquals(3.5, f.budget().reserved(), 1e-9, "会话C 仍占着 3.5");
        assertEquals(4.0, f.budget().available(), 1e-9);
        assertConservation(f.budget(), 10.0);

        // 会话视图能区分"花掉"与"占着"
        var infoC = f.sessions().info(c);
        assertEquals(0.5, infoC.consumed(), 1e-9);
        assertEquals(3.5, infoC.held(), 1e-9);
        assertEquals(SessionState.ACTIVE, infoC.state());
    }

    @Test
    void sameSeedAndInputGiveSameResultWithOrWithoutSession() {
        Fixture f = newFixture(100.0);
        String sessionId = f.sessions().create("health", 10.0, null).sessionId();

        QueryResult direct = f.service().query(
                new QueryRequest("health", "age-30-40", 1.0, 1e-5, 5, 42L));
        QueryResult viaSession = f.service().query(sessionRequest(sessionId, 1.0, 42L));
        log.info("兼容性: 直接路径={}, 会话路径={}", direct.value(), viaSession.value());
        assertEquals(QueryStatus.OK, direct.status());
        assertEquals(QueryStatus.OK, viaSession.status());
        assertEquals(direct.value(), viaSession.value(),
                "相同种子、相同输入的查询结果必须与是否走会话无关");
    }
}
