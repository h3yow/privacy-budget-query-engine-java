package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.BudgetAudit;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.InvalidPrivacyParameterException;
import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话额度的申请、并发消费、超时回收与归还语义。 */
class SessionBudgetManagerTest {

    private static final Logger log = LoggerFactory.getLogger(SessionBudgetManagerTest.class);

    private SessionBudgetManager newManager(PrivacyBudget budget, MutableClock clock) {
        return new SessionBudgetManager(Map.of("health", budget), clock, 1_000L);
    }

    @Test
    void applyReservesBudgetAndRejectsOverdraft() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        SessionBudgetManager manager = newManager(budget, new MutableClock(0L));

        SessionBudget session = manager.apply("health", 2.0);
        log.info("申请成功: sessionId={}, granted={}, 域可用={}, 域被占={}",
                session.id(), session.granted(), budget.remaining(), budget.reserved());
        assertEquals(3.0, budget.remaining(), 1e-9, "申请后可用余额减少");
        assertEquals(2.0, budget.reserved(), 1e-9, "申请额度被占着但未花掉");
        assertEquals(0.0, budget.consumed(), 1e-9);

        // 超过当前可用预算的申请必须失败
        InvalidPrivacyParameterException over = assertThrows(InvalidPrivacyParameterException.class,
                () -> manager.apply("health", 3.5));
        log.info("超额申请被拒绝: {}", over.getMessage());
        // 非法参数与未知域也必须失败
        assertThrows(InvalidPrivacyParameterException.class, () -> manager.apply("health", 0.0));
        assertThrows(InvalidPrivacyParameterException.class, () -> manager.apply("health", -1.0));
        assertThrows(InvalidPrivacyParameterException.class, () -> manager.apply("health", Double.NaN));
        assertThrows(InvalidPrivacyParameterException.class, () -> manager.apply("unknown", 1.0));

        BudgetAudit audit = budget.audit();
        log.info("审计视图: {}", audit);
        assertEquals(5.0, audit.consumed() + audit.reserved() + audit.available(), 1e-9,
                "已花 + 被占 + 可用 == 总额");
        assertEquals(1, manager.activeSessionCount());
    }

    @Test
    void concurrentSessionConsumptionNeverOverspends() throws Exception {
        PrivacyBudget budget = new PrivacyBudget("health", 100.0);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = newManager(budget, clock);
        SessionBudget session = manager.apply("health", 5.0);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<SessionBudget.ConsumeOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                return session.tryConsume(1.0, clock.millis());
            });
        }
        List<Future<SessionBudget.ConsumeOutcome>> futures = new ArrayList<>();
        for (Callable<SessionBudget.ConsumeOutcome> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();

        Map<SessionBudget.ConsumeOutcome, Integer> tally = new ConcurrentHashMap<>();
        for (Future<SessionBudget.ConsumeOutcome> f : futures) {
            tally.merge(f.get(), 1, Integer::sum);
        }
        pool.shutdown();

        log.info("并发消费会话额度: 线程数={}, 成功={}, 额度不足={}, 会话已耗={}, 账本被占={}",
                threads, tally.getOrDefault(SessionBudget.ConsumeOutcome.OK, 0),
                tally.getOrDefault(SessionBudget.ConsumeOutcome.INSUFFICIENT, 0),
                session.consumed(), budget.reserved());

        assertEquals(5, tally.getOrDefault(SessionBudget.ConsumeOutcome.OK, 0),
                "会话额度 5.0 / 每次 1.0，恰好允许 5 次成功");
        assertEquals(threads - 5, tally.getOrDefault(SessionBudget.ConsumeOutcome.INSUFFICIENT, 0));
        assertEquals(5.0, session.consumed(), 1e-9, "累计消耗不超过申请量");
        assertEquals(5.0, budget.consumed(), 1e-9, "被占额度全部转为实耗");
        assertEquals(0.0, budget.reserved(), 1e-9);
    }

    @Test
    void closeReturnsUnusedBudgetAndIsIdempotent() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = newManager(budget, clock);
        SessionBudget session = manager.apply("health", 3.0);

        assertEquals(SessionBudget.ConsumeOutcome.OK, session.tryConsume(1.0, clock.millis()));
        double returned = manager.close(session.id()) ? 2.0 : -1.0;
        log.info("主动结束: 归还={}, 账本 已耗={} 被占={} 可用={}",
                returned, budget.consumed(), budget.reserved(), budget.remaining());

        assertEquals(1.0, budget.consumed(), 1e-9, "已花掉的不退还");
        assertEquals(0.0, budget.reserved(), 1e-9, "结束后不再占用");
        assertEquals(4.0, budget.remaining(), 1e-9, "未花完的归还可用余额");
        assertEquals(0, manager.activeSessionCount());

        // 幂等：重复关闭不会重复归还
        double again = session.close();
        log.info("重复关闭: 再次归还={}", again);
        assertEquals(0.0, again, 1e-9);
        assertEquals(4.0, budget.remaining(), 1e-9, "重复归还不允许把账算多");
        assertEquals(false, manager.close(session.id()), "未知会话关闭返回 false");
    }

    @Test
    void idleTimeoutReleasesReservationAutomatically() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = newManager(budget, clock);
        SessionBudget session = manager.apply("health", 2.0);

        assertEquals(SessionBudget.ConsumeOutcome.OK, session.tryConsume(0.5, clock.millis()));
        clock.advance(999L);
        assertEquals(SessionBudget.ConsumeOutcome.OK, session.tryConsume(0.5, clock.millis()),
                "超时前一刻仍可正常使用");

        clock.advance(1_000L); // 距上次访问已满 1 秒空闲
        SessionBudget.ConsumeOutcome outcome = session.tryConsume(0.5, clock.millis());
        log.info("空闲超时后消费: outcome={}, 账本 已耗={} 被占={} 可用={}",
                outcome, budget.consumed(), budget.reserved(), budget.remaining());

        assertEquals(SessionBudget.ConsumeOutcome.EXPIRED, outcome);
        assertEquals(1.0, budget.consumed(), 1e-9, "超时前已花掉的部分保留");
        assertEquals(0.0, budget.reserved(), 1e-9, "剩余占用被释放");
        assertEquals(4.0, budget.remaining(), 1e-9, "未花完部分归还可用余额");
        assertEquals(SessionBudget.ConsumeOutcome.CLOSED, session.tryConsume(0.5, clock.millis()),
                "回收后的会话不可再用");
    }

    @Test
    void sweepReclaimsIdleSessionsAndKeepsActiveOnes() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = newManager(budget, clock);

        SessionBudget idle = manager.apply("health", 1.0);
        SessionBudget active = manager.apply("health", 1.0);
        clock.advance(500L);
        // 活跃会话在超时前正常使用，刷新访问时间
        assertEquals(SessionBudget.ConsumeOutcome.OK, active.tryConsume(0.5, clock.millis()));
        clock.advance(600L); // 空闲会话已闲置 1100ms 超时；活跃会话仅闲置 600ms

        int swept = manager.sweepExpired();
        log.info("清扫: 回收数={}, 存活会话数={}, 账本 被占={} 可用={}",
                swept, manager.activeSessionCount(), budget.reserved(), budget.remaining());

        assertEquals(1, swept, "只回收空闲超时的会话");
        assertEquals(1, manager.activeSessionCount());
        assertEquals(0.5, budget.reserved(), 1e-9, "活跃会话的未用占用保留");
        assertEquals(4.0, budget.remaining(), 1e-9, "空闲会话的占用已归还（活跃会话实耗0.5+占用0.5）");
        assertTrue(idle.isClosed());
    }

    @Test
    void refundRollsBackConsumptionWithinSession() {
        PrivacyBudget budget = new PrivacyBudget("health", 5.0);
        MutableClock clock = new MutableClock(0L);
        SessionBudgetManager manager = newManager(budget, clock);
        SessionBudget session = manager.apply("health", 2.0);

        assertEquals(SessionBudget.ConsumeOutcome.OK, session.tryConsume(1.0, clock.millis()));
        session.refund(1.0);
        log.info("会话内回滚: 会话剩余={}, 账本 已耗={} 被占={}", session.remaining(), budget.consumed(), budget.reserved());

        assertEquals(2.0, session.remaining(), 1e-9, "回滚退回会话额度内");
        assertEquals(0.0, budget.consumed(), 1e-9);
        assertEquals(2.0, budget.reserved(), 1e-9);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> session.refund(0.5));
        log.info("超额回滚被拒绝: {}", e.getMessage());
    }
}
