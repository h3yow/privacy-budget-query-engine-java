package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataSource;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.BudgetAudit;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryRequest;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryResult;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryStatus;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudget;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudgetManager;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话额度在查询编排中的语义：扣减、拒绝、回收、审计守恒与兼容性。 */
class SessionQueryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SessionQueryServiceTest.class);

    private record Fixture(AggregationQueryService service, PrivacyBudget budget,
                           SessionBudgetManager sessions, MutableClock clock) {
    }

    private Fixture newFixture(double domainBudget) {
        PrivacyBudget budget = new PrivacyBudget("health", domainBudget);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = new SessionBudgetManager(
                Map.of("health", budget), clock, 1_000L);
        return new Fixture(new AggregationQueryService(new LocalDataSource(),
                Map.of("health", budget), manager), budget, manager, clock);
    }

    private QueryRequest sessionRequest(String sessionId, double epsilon, long seed) {
        return new QueryRequest("health", "age-30-40", epsilon, 1e-5, 5, seed, sessionId);
    }

    @Test
    void sessionQueryConsumesFromSessionAndReportsSessionRemaining() {
        Fixture f = newFixture(5.0);
        SessionBudget session = f.sessions().apply("health", 3.0);

        QueryResult r = f.service().query(sessionRequest(session.id(), 1.0, 42L));
        log.info("会话查询: status={}, value={}, epsilonConsumed={}, 剩余额度={}",
                r.status(), r.value(), r.epsilonConsumed(), r.budgetRemaining());

        assertEquals(QueryStatus.OK, r.status());
        assertEquals(1.0, r.epsilonConsumed(), 1e-9);
        assertEquals(2.0, r.budgetRemaining(), 1e-9, "会话查询报告会话内剩余额度");
        assertEquals(1.0, f.budget().consumed(), 1e-9, "会话消费转为实耗");
        assertEquals(2.0, f.budget().reserved(), 1e-9, "会话未用部分仍被占着");
    }

    @Test
    void expiredSessionIsRejectedWithDistinguishableStatus() {
        Fixture f = newFixture(5.0);
        SessionBudget session = f.sessions().apply("health", 2.0);
        f.clock().advance(1_000L); // 空闲超时

        QueryResult r = f.service().query(sessionRequest(session.id(), 1.0, 7L));
        log.info("过期会话查询: status={}, value={}, reason={}", r.status(), r.value(), r.reason());

        assertEquals(QueryStatus.REJECTED_SESSION_INVALID, r.status(),
                "过期会话必须与参数越界、匿名度不足明确区分");
        assertNull(r.value(), "失效会话不得返回任何原始或扰动结果");
        assertEquals(0.0, r.epsilonConsumed(), 1e-9);
        assertEquals(0.0, f.budget().consumed(), 1e-9, "失效会话不得消耗预算");
        assertEquals(0.0, f.budget().reserved(), 1e-9, "过期会话的占用被自动归还");
        assertEquals(5.0, f.budget().remaining(), 1e-9, "归还后可用余额恢复");
    }

    @Test
    void closedUnknownAndCrossDomainSessionsAreRejected() {
        PrivacyBudget health = new PrivacyBudget("health", 5.0);
        PrivacyBudget finance = new PrivacyBudget("finance", 5.0);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = new SessionBudgetManager(
                Map.of("health", health, "finance", finance), clock, 1_000L);
        AggregationQueryService service = new AggregationQueryService(new LocalDataSource(),
                Map.of("health", health, "finance", finance), manager);

        SessionBudget closed = manager.apply("health", 1.0);
        manager.close(closed.id());
        SessionBudget financeSession = manager.apply("finance", 1.0);

        List<QueryRequest> bad = List.of(
                sessionRequest(closed.id(), 0.5, 1L),              // 已结束
                sessionRequest("no-such-session", 0.5, 2L),        // 不存在
                sessionRequest(financeSession.id(), 0.5, 3L));     // 跨域使用
        for (QueryRequest req : bad) {
            QueryResult r = service.query(req);
            log.info("失效会话拒绝: sessionId={}, status={}, reason={}",
                    req.sessionId(), r.status(), r.reason());
            assertEquals(QueryStatus.REJECTED_SESSION_INVALID, r.status());
            assertNull(r.value());
            assertEquals(0.0, r.epsilonConsumed(), 1e-9);
        }
        assertEquals(0.0, health.consumed(), 1e-9, "拒绝路径不得消耗预算");
        assertEquals(0.0, finance.consumed(), 1e-9);
    }

    @Test
    void sessionExhaustionIsBudgetExhaustedNotSessionInvalid() {
        Fixture f = newFixture(5.0);
        SessionBudget session = f.sessions().apply("health", 1.5);

        QueryResult ok = f.service().query(sessionRequest(session.id(), 1.0, 1L));
        QueryResult denied = f.service().query(sessionRequest(session.id(), 1.0, 2L));
        log.info("会话额度耗尽: ok={}, denied={} ({})", ok.status(), denied.status(), denied.reason());

        assertEquals(QueryStatus.OK, ok.status());
        assertEquals(QueryStatus.REJECTED_BUDGET_EXHAUSTED, denied.status(),
                "会话仍有效但额度不足，应与会话失效区分");
        assertNull(denied.value());
        assertEquals(1.0, f.budget().consumed(), 1e-9, "失败扣减不得发生");
    }

    @Test
    void sameSeedSameResultWithAndWithoutSession() {
        Fixture f = newFixture(100.0);
        SessionBudget session = f.sessions().apply("health", 10.0);

        QueryResult direct = f.service().query(
                new QueryRequest("health", "age-30-40", 1.0, 1e-5, 5, 42L));
        QueryResult viaSession = f.service().query(sessionRequest(session.id(), 1.0, 42L));
        log.info("兼容性校验: 直扣={}, 会话={}", direct.value(), viaSession.value());

        assertEquals(QueryStatus.OK, direct.status());
        assertEquals(QueryStatus.OK, viaSession.status());
        assertEquals(direct.value(), viaSession.value(),
                "相同种子、相同输入的查询结果必须与老方式一致");
    }

    @Test
    void concurrentSessionQueriesNeverOverspend() throws Exception {
        Fixture f = newFixture(100.0);
        SessionBudget session = f.sessions().apply("health", 5.0);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<QueryResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long seed = i;
            tasks.add(() -> {
                start.await();
                return f.service().query(sessionRequest(session.id(), 1.0, seed));
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

        log.info("并发会话查询: 成功={}, 额度不足={}, 消耗合计={}, 会话剩余={}",
                tally.getOrDefault(QueryStatus.OK, 0),
                tally.getOrDefault(QueryStatus.REJECTED_BUDGET_EXHAUSTED, 0),
                consumedSum, session.remaining());

        assertEquals(5, tally.getOrDefault(QueryStatus.OK, 0),
                "会话额度 5.0 / 每次 1.0，恰好允许 5 次成功");
        assertEquals(threads - 5, tally.getOrDefault(QueryStatus.REJECTED_BUDGET_EXHAUSTED, 0));
        assertEquals(5.0, consumedSum, 1e-9, "累计消耗不超过申请量");
        assertEquals(5.0, f.budget().consumed(), 1e-9);
        assertEquals(0.0, f.budget().reserved(), 1e-9);
    }

    @Test
    void auditConservationAcrossMixedPaths() {
        Fixture f = newFixture(5.0);
        SessionBudget s1 = f.sessions().apply("health", 2.0);
        SessionBudget s2 = f.sessions().apply("health", 1.0);

        f.service().query(sessionRequest(s1.id(), 1.0, 1L));                 // 会话实耗 1.0
        f.service().query(new QueryRequest("health", "age-30-40", 0.5, 1e-5, 5, 2L)); // 直扣 0.5
        f.sessions().close(s2.id());                                          // s2 未用 1.0 归还
        f.clock().advance(1_000L);
        f.service().query(sessionRequest(s1.id(), 0.5, 3L));                 // s1 过期，归还剩余 1.0

        BudgetAudit audit = f.service().audit("health");
        log.info("混合路径后审计: {}", audit);

        assertEquals(5.0, audit.total(), 1e-9);
        assertEquals(1.5, audit.consumed(), 1e-9, "真正花掉的：会话 1.0 + 直扣 0.5");
        assertEquals(0.0, audit.reserved(), 1e-9, "所有会话均已结束或过期，无占用");
        assertEquals(3.5, audit.available(), 1e-9);
        assertEquals(audit.total(), audit.consumed() + audit.reserved() + audit.available(), 1e-9,
                "已花掉的 + 被占着的 + 还能用的 == 总额");
    }

    @Test
    void releasedBudgetIsImmediatelyReusableByOthers() {
        Fixture f = newFixture(5.0);
        SessionBudget session = f.sessions().apply("health", 5.0); // 占满全域

        QueryResult blocked = f.service().query(
                new QueryRequest("health", "age-30-40", 1.0, 1e-5, 5, 1L));
        log.info("额度被占满时直扣: status={}", blocked.status());
        assertEquals(QueryStatus.REJECTED_BUDGET_EXHAUSTED, blocked.status(),
                "会话占着额度时，同域其他请求只能等");

        f.sessions().close(session.id());
        QueryResult after = f.service().query(
                new QueryRequest("health", "age-30-40", 1.0, 1e-5, 5, 1L));
        log.info("会话结束后直扣: status={}, 剩余={}", after.status(), after.budgetRemaining());
        assertEquals(QueryStatus.OK, after.status(), "归还后其他请求立即可用");
        assertEquals(4.0, after.budgetRemaining(), 1e-9);
    }

    @Test
    void failedQueryRollsBackIntoSession() {
        Fixture f = newFixture(5.0);
        SessionBudget session = f.sessions().apply("health", 2.0);

        // 匿名度不足：不消耗会话额度
        QueryResult anon = f.service().query(
                new QueryRequest("health", "rare-cohort", 1.0, 1e-5, 5, 1L, session.id()));
        log.info("会话内匿名度不足: status={}, 会话剩余={}", anon.status(), session.remaining());
        assertEquals(QueryStatus.REJECTED_INSUFFICIENT_ANONYMITY, anon.status());
        assertEquals(2.0, session.remaining(), 1e-9, "前置校验失败不得消耗会话额度");

        // 参数越界：不消耗会话额度
        QueryResult invalid = f.service().query(sessionRequest(session.id(), 0.0, 2L));
        assertEquals(QueryStatus.REJECTED_INVALID_PARAMETER, invalid.status());
        assertEquals(2.0, session.remaining(), 1e-9);

        BudgetAudit audit = f.service().audit("health");
        assertEquals(0.0, audit.consumed(), 1e-9);
        assertEquals(2.0, audit.reserved(), 1e-9);
        assertEquals(3.0, audit.available(), 1e-9);
        assertTrue(audit.consumed() + audit.reserved() + audit.available() == audit.total());
    }
}
