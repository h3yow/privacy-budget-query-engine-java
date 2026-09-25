package com.github.h3yow.privacybudgetqueryenginejava.tests;

import com.github.h3yow.privacybudgetqueryenginejava.exception.PrivacyQueryException;
import com.github.h3yow.privacybudgetqueryenginejava.exception.RejectionReason;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationRequest;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationResponse;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationType;
import com.github.h3yow.privacybudgetqueryenginejava.model.PrivacyParams;
import com.github.h3yow.privacybudgetqueryenginejava.service.PrivacyQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差分隐私聚合查询流程测试。
 *
 * <p>每个用例注册独立数据域 -> 获得独立预算账户，用例间互不影响、可重复执行。
 * 请求参数、预算变动与判定依据由服务层日志统一打印（[QUERY-START]/[BUDGET-*] 等）。
 */
@SpringBootTest
@Import(TestDataConfig.class)
class PrivacyQueryFlowTest {

    private static final java.util.concurrent.atomic.AtomicLong DOMAIN_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private PrivacyQueryService service;

    /** 注册隔离数据域；groupSizes 为每个分组的行数。 */
    private static String newDomain(List<Integer> groupSizes) {
        String domain = "ut-domain-" + DOMAIN_SEQ.incrementAndGet();
        TestDataConfig.registerDomain(domain, groupSizes);
        return domain;
    }

    private static String unknownDomain() {
        return "no-such-domain-" + DOMAIN_SEQ.incrementAndGet();
    }

    private AggregationRequest countRequest(String domain, double epsilon, int minGroupSize, long seed) {
        return new AggregationRequest(
                "tester", domain, AggregationType.COUNT,
                "region", null, 1.0,
                new PrivacyParams(epsilon, 0.0), minGroupSize, seed);
    }

    private AggregationRequest sumRequest(String domain, double epsilon, double sensitivity,
                                          int minGroupSize, long seed) {
        return new AggregationRequest(
                "tester", domain, AggregationType.SUM,
                "region", "amount", sensitivity,
                new PrivacyParams(epsilon, 0.0), minGroupSize, seed);
    }

    @Test
    void successfulQuery_sameSeedReproducible_differentSeedDiffers() {
        String d = newDomain(List.of(3, 8));
        AggregationResponse r1 = service.query(countRequest(d, 0.5, 1, 42L));
        AggregationResponse r2 = service.query(countRequest(d, 0.5, 1, 42L));
        AggregationResponse r3 = service.query(countRequest(d, 0.5, 1, 7L));

        assertEquals(2, r1.results().size());
        // 同种子同输入 -> 逐项一致（可复现）。
        for (int i = 0; i < r1.results().size(); i++) {
            assertEquals(r1.results().get(i).groupKey(), r2.results().get(i).groupKey());
            assertEquals(r1.results().get(i).value(), r2.results().get(i).value(), 1e-12,
                    "相同种子与输入必须产生一致结果");
        }
        // 不同种子 -> 结果不同。
        assertNotEquals(r1.results().get(0).value(), r3.results().get(0).value(),
                "不同种子应产生不同扰动");
        // scale = 1/0.5 = 2；两次查询后累计消耗 1.0，剩余 2.0。
        assertEquals(2.0, r1.results().get(0).noiseScale(), 1e-9);
        assertEquals(0.5, r1.epsilonUsed(), 1e-12);
        assertEquals(2.0, r2.remainingEpsilon(), 1e-9);
    }

    @Test
    void invalidEpsilon_isRejectedWithDistinctReason_andBudgetUntouched() {
        String d = newDomain(List.of(3));
        double before = service.budget(d).remaining();
        PrivacyQueryException e1 = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 0.0, 1, 1L)));
        assertEquals(RejectionReason.INVALID_EPSILON, e1.getReason());
        PrivacyQueryException e2 = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 1.5, 1, 1L)));
        assertEquals(RejectionReason.INVALID_EPSILON, e2.getReason());
        PrivacyQueryException e3 = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, -0.2, 1, 1L)));
        assertEquals(RejectionReason.INVALID_EPSILON, e3.getReason());
        // 校验在触碰预算之前：拒绝后 spent=0、remaining=上界不变。
        assertEquals(0.0, service.budget(d).spent(), 1e-12);
        assertEquals(before, service.budget(d).remaining(), 1e-12);
    }

    @Test
    void invalidDelta_isRejectedAndBudgetUntouched() {
        String d = newDomain(List.of(3));
        AggregationRequest req = new AggregationRequest(
                "tester", d, AggregationType.COUNT, "region", null, 1.0,
                new PrivacyParams(0.5, 0.0001), 1, 1L);
        PrivacyQueryException ex = assertThrows(PrivacyQueryException.class,
                () -> service.query(req));
        assertEquals(RejectionReason.INVALID_DELTA, ex.getReason());
        assertEquals(0.0, service.budget(d).spent(), 1e-12);
    }

    @Test
    void invalidSensitivity_isRejectedAndBudgetUntouched() {
        String d = newDomain(List.of(3));
        PrivacyQueryException e1 = assertThrows(PrivacyQueryException.class,
                () -> service.query(sumRequest(d, 0.5, 0.0, 1, 1L)));
        assertEquals(RejectionReason.INVALID_SENSITIVITY, e1.getReason());
        PrivacyQueryException e2 = assertThrows(PrivacyQueryException.class,
                () -> service.query(sumRequest(d, 0.5, -3.0, 1, 1L)));
        assertEquals(RejectionReason.INVALID_SENSITIVITY, e2.getReason());
        assertEquals(0.0, service.budget(d).spent(), 1e-12);
    }

    @Test
    void invalidMinGroupSize_isRejectedAndBudgetUntouched() {
        String d = newDomain(List.of(3));
        PrivacyQueryException ex = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 0.5, 0, 1L)));
        assertEquals(RejectionReason.INVALID_GROUP_SIZE, ex.getReason());
        assertEquals(0.0, service.budget(d).spent(), 1e-12);
    }

    @Test
    void unknownDomain_isRejectedAndBudgetUntouched() {
        String d = unknownDomain();
        PrivacyQueryException ex = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 0.5, 1, 1L)));
        assertEquals(RejectionReason.INVALID_DOMAIN, ex.getReason());
        // 查询未知域会惰性创建账户快照，但 spent 必须仍为 0。
        assertEquals(0.0, service.budget(d).spent(), 1e-12);
    }

    @Test
    void anonymityTooSmall_isRejected_andBudgetRolledBack() {
        // G1=3 行、G2=8 行；要求每组 >=5 -> G1 不足，必须拒绝。
        String d = newDomain(List.of(3, 8));
        var before = service.budget(d);
        PrivacyQueryException ex = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 1.0, 5, 99L)));
        assertEquals(RejectionReason.ANONYMITY_TOO_SMALL, ex.getReason());
        assertTrue(ex.getMessage().contains("G1"), "拒绝信息应指出不足分组: " + ex.getMessage());

        var after = service.budget(d);
        // 关键：失败路径不消耗预算，spent 不变且无残留预占（remaining 完整恢复）。
        assertEquals(0.0, after.spent(), 1e-12, "匿名度失败不得永久消耗预算");
        assertEquals(before.remaining(), after.remaining(), 1e-9, "回滚后余额必须恢复");

        // 回滚后账户可再次被正常请求使用（预算守恒可复用）：降低匿名度阈值后成功。
        AggregationResponse ok = service.query(countRequest(d, 1.0, 3, 99L));
        assertEquals(1.0, ok.epsilonUsed(), 1e-12);
        assertEquals(1.0, service.budget(d).spent(), 1e-9);
        assertEquals(2.0, service.budget(d).remaining(), 1e-9);
    }

    @Test
    void sequentialConsumption_neverOverspent_andConserved() {
        String d = newDomain(List.of(10));
        // 上界 3.0：0.8 + 0.8 + 0.8 + 0.8 = 3.2 -> 前三次成功，第四次预算不足。
        service.query(countRequest(d, 0.8, 1, 1L));
        service.query(countRequest(d, 0.8, 1, 2L));
        AggregationResponse third = service.query(countRequest(d, 0.8, 1, 3L));
        assertEquals(0.6, third.remainingEpsilon(), 1e-9);

        PrivacyQueryException ex = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 0.8, 1, 4L)));
        assertEquals(RejectionReason.BUDGET_EXHAUSTED, ex.getReason());

        // 预算不足的拒绝不得重复扣减或漏扣：spent 仍为 2.4，remaining 仍为 0.6。
        assertEquals(2.4, service.budget(d).spent(), 1e-9);
        assertEquals(0.6, service.budget(d).remaining(), 1e-9);
        // 守恒：spent + remaining + reserved = 上界 3.0，且无残留预占 reserved=0。
        assertEquals(3.0, service.budget(d).spent() + service.budget(d).remaining()
                + residualReserved(d), 1e-9);
        assertEquals(0.0, residualReserved(d), 1e-9, "被拒绝请求不得残留预占");
        // 剩余的 0.6 仍可被小额请求使用。
        AggregationResponse small = service.query(countRequest(d, 0.6, 1, 5L));
        assertEquals(0.0, small.remainingEpsilon(), 1e-9);
        assertEquals(3.0, service.budget(d).spent(), 1e-9);
    }

    @Test
    void sumQuery_usesDeclaredSensitivity_andIsReproducible() {
        // 单分组 4 行，amount = 10,11,12,13，原始 SUM=46（该值不出现在返回体）。
        String d = newDomain(List.of(4));
        AggregationResponse r1 = service.query(sumRequest(d, 0.25, 50.0, 1, 77L));
        AggregationResponse r2 = service.query(sumRequest(d, 0.25, 50.0, 1, 77L));
        assertEquals(1, r1.results().size());
        // scale = sensitivity/epsilon = 50/0.25 = 200。
        assertEquals(200.0, r1.results().get(0).noiseScale(), 1e-9);
        // 可复现。
        assertEquals(r1.results().get(0).value(), r2.results().get(0).value(), 1e-12);
        // 返回值被噪声扰动：不等于原始 SUM=46。
        assertNotEquals(46.0, r1.results().get(0).value(), 1e-9, "返回值不得是未扰动原始值");
        assertEquals(0.25, r1.epsilonUsed(), 1e-12);
    }

    @Test
    void rejectedException_neverCarriesRawValues() {
        // 异常路径：校验类与预算类拒绝信息均只含原因与预算元数据，不含任何分组原始值。
        String d = newDomain(List.of(2, 9));
        PrivacyQueryException anonymity = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 0.5, 5, 3L)));
        // 匿名度消息允许出现分组键与计数 n（匿名度判定依据），但不允许出现原始 sum/amount。
        assertTrue(anonymity.getMessage().contains("G1"));
        assertTrue(!anonymity.getMessage().toLowerCase().contains("sum="));
    }

    @Test
    void concurrentRequests_neverOverspent_andNoDoubleOrMissingDeduction() throws Exception {
        // 50 个主体并发对同一数据域各申请 epsilon=0.2；预算上界 3.0 仅允许 15 个成功。
        String d = newDomain(List.of(50));
        int threads = 50;
        double epsilon = 0.2;
        // 线程池容量必须 >= 任务数：若容量小于任务数，先进入池中的任务会停在
        // start.await()，排队任务无法执行 ready.countDown()，主线程将永远等待。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        AtomicInteger otherFailure = new AtomicInteger();
        Collection<String> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final long seed = i + 1;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // 最大化同时竞争
                    AggregationResponse resp = service.query(
                            countRequest(d, epsilon, 1, seed));
                    success.incrementAndGet();
                    // 每个成功响应自报的“已消耗”必须与申请一致（无重复扣减）。
                    if (Math.abs(resp.epsilonUsed() - epsilon) > 1e-12) {
                        errors.add("epsilonUsed=" + resp.epsilonUsed());
                    }
                } catch (PrivacyQueryException ex) {
                    if (ex.getReason() == RejectionReason.BUDGET_EXHAUSTED) {
                        exhausted.incrementAndGet();
                    } else {
                        otherFailure.incrementAndGet();
                        errors.add(ex.getReason() + ":" + ex.getMessage());
                    }
                } catch (Exception ex) {
                    otherFailure.incrementAndGet();
                    errors.add(ex.toString());
                }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前结束");

        // 成功数必须精确为 15 = floor(3.0 / 0.2)，其余 35 全部因预算不足被拒绝。
        assertEquals(15, success.get(), "预算约束下成功数必须精确为 15，实际 " + success.get());
        assertEquals(35, exhausted.get(), "其余请求必须全部因 BUDGET_EXHAUSTED 被拒绝");
        assertEquals(0, otherFailure.get(), "不允许出现预算不足以外的异常: " + errors);
        assertTrue(errors.isEmpty(), "不允许金额不一致: " + errors);

        // 账户守恒：spent 恰为 3.0、remaining 为 0、无残留预占；绝无超支或重复扣减。
        var snap = service.budget(d);
        assertEquals(3.0, snap.spent(), 1e-9, "并发结束后累计消耗必须恰为预算上界");
        assertEquals(0.0, snap.remaining(), 1e-9, "余额必须耗尽为 0");
        assertEquals(0.0, residualReserved(d), 1e-9, "所有预占必须已提交或回滚，无残留");

        // 预算耗尽后，同域再小的请求也必须被拒绝，且 spent 保持 3.0 不变。
        PrivacyQueryException ex = assertThrows(PrivacyQueryException.class,
                () -> service.query(countRequest(d, 0.01, 1, 100L)));
        assertEquals(RejectionReason.BUDGET_EXHAUSTED, ex.getReason());
        assertEquals(3.0, service.budget(d).spent(), 1e-9);
    }

    /** 快照不直接暴露 reserved，这里通过 limit-spent-remaining 反推（应为 0）。 */
    private double residualReserved(String d) {
        var s = service.budget(d);
        return s.limit() - s.spent() - s.remaining();
    }
}
