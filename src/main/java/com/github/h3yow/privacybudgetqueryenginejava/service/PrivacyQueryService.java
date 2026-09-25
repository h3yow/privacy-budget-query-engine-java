package com.github.h3yow.privacybudgetqueryenginejava.service;

import com.github.h3yow.privacybudgetqueryenginejava.budget.BudgetManager;
import com.github.h3yow.privacybudgetqueryenginejava.data.DataRow;
import com.github.h3yow.privacybudgetqueryenginejava.data.LocalDataRepository;
import com.github.h3yow.privacybudgetqueryenginejava.dp.LaplaceMechanism;
import com.github.h3yow.privacybudgetqueryenginejava.dp.PrivacyParamsValidator;
import com.github.h3yow.privacybudgetqueryenginejava.exception.PrivacyQueryException;
import com.github.h3yow.privacybudgetqueryenginejava.exception.RejectionReason;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationRequest;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationResponse;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationType;
import com.github.h3yow.privacybudgetqueryenginejava.model.BudgetSnapshot;
import com.github.h3yow.privacybudgetqueryenginejava.model.GroupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 差分隐私聚合查询编排。
 *
 * <p>固定流程（每一步都打印判定依据）：
 * <ol>
 *   <li>参数语义校验（epsilon/delta/灵敏度/匿名度）——不触碰预算；</li>
 *   <li>数据域校验——不触碰预算；</li>
 *   <li>原子预占预算 reserve——余额不足直接拒绝，账户无任何变化；</li>
 *   <li>最小匿名度校验——任一分组不足则回滚预占并拒绝（不消耗预算）；</li>
 *   <li>原始聚合 + 拉普拉斯扰动（种子决定噪声，可复现）；</li>
 *   <li>提交预算 commit。</li>
 * </ol>
 * 任何异常路径都只抛出异常、绝不返回原始聚合值。
 */
@Service
public class PrivacyQueryService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyQueryService.class);

    /** 全域聚合使用的分组键。 */
    public static final String WHOLE_DOMAIN_KEY = "*";

    private final LocalDataRepository repository;
    private final BudgetManager budgetManager;
    private final LaplaceMechanism mechanism;
    private final PrivacyParamsValidator validator;

    public PrivacyQueryService(LocalDataRepository repository,
                               BudgetManager budgetManager,
                               LaplaceMechanism mechanism,
                               PrivacyParamsValidator validator) {
        this.repository = repository;
        this.budgetManager = budgetManager;
        this.mechanism = mechanism;
        this.validator = validator;
    }

    /** 一个分组的原始统计量，仅在服务内部流转，绝不进入返回体。 */
    private record RawAggregate(String groupKey, long count, double sum) {
    }

    public AggregationResponse query(AggregationRequest request) {
        log.info("[QUERY-START] requester={} domain={} type={} groupColumn='{}' sumColumn='{}' "
                        + "sensitivity={} epsilon={} delta={} minGroupSize={} seed={}",
                request.requesterId(), request.domain(), request.aggregationType(),
                request.groupColumn(), request.sumColumn(), request.sensitivity(),
                request.privacy() == null ? null : request.privacy().epsilon(),
                request.privacy() == null ? null : request.privacy().delta(),
                request.minGroupSize(), request.seed());

        // 1) COUNT 的灵敏度恒为 1；SUM 使用请求方声明的 L1 灵敏度上界。
        double sensitivity = request.aggregationType() == AggregationType.COUNT
                ? 1.0
                : request.sensitivity();

        // 1) 参数校验（拒绝时预算未触碰）。
        validator.validate(request.privacy(), sensitivity, request.minGroupSize());

        String domain = request.domain();
        if (domain == null || domain.isBlank() || !repository.knowsDomain(domain)) {
            log.warn("[REJECT] reason=INVALID_DOMAIN domain={} 判定=未知数据域，预算不触碰", domain);
            throw new PrivacyQueryException(RejectionReason.INVALID_DOMAIN,
                    "未知数据域: " + domain);
        }

        double epsilon = request.privacy().epsilon();

        // 2) 计算原始分组聚合（仅用于匿名度判定与随后的加噪，不对外暴露）。
        List<RawAggregate> rawAggregates = aggregate(request, domain);
        log.info("[RAW-AGGREGATE] domain={} groups={} 判定=原始值仅用于匿名度校验与加噪，不输出",
                domain, rawAggregates.size());

        // 3) 原子预占预算。
        BudgetSnapshot before = budgetManager.snapshot(domain);
        log.info("[BUDGET-BEFORE] domain={} spent={} remaining={} requestedEpsilon={}",
                domain, before.spent(), before.remaining(), epsilon);
        budgetManager.reserve(domain, epsilon);
        BudgetSnapshot afterReserve = budgetManager.snapshot(domain);
        log.info("[BUDGET-RESERVED] domain={} spent={} reserved={} remaining={} 判定=预占成功",
                domain, afterReserve.spent(),
                afterReserve.limit() - afterReserve.spent() - afterReserve.remaining(),
                afterReserve.remaining());

        boolean committed = false;
        try {
            // 4) 最小匿名度校验：不足则抛异常，由 finally 回滚预占。
            List<String> tooSmall = new ArrayList<>();
            for (RawAggregate agg : rawAggregates) {
                if (agg.count() < request.minGroupSize()) {
                    tooSmall.add(agg.groupKey() + "(n=" + agg.count() + ")");
                }
            }
            if (!tooSmall.isEmpty()) {
                log.warn("[ANONYMITY-FAIL] domain={} 不足分组={} minGroupSize={} 判定=拒绝并回滚预占 epsilon={}",
                        domain, tooSmall, request.minGroupSize(), epsilon);
                throw new PrivacyQueryException(RejectionReason.ANONYMITY_TOO_SMALL,
                        String.format("分组规模低于最小匿名度 %s：%s（预算将回滚，不消耗）",
                                request.minGroupSize(), tooSmall));
            }

            // 5) 加噪。每个分组使用由 (seed, 分组序号) 派生的独立种子，
            //    保证分组顺序稳定时结果可复现，且不同分组不共享同一噪声。
            double scale = mechanism.scale(sensitivity, epsilon);
            List<GroupResult> results = new ArrayList<>();
            for (int i = 0; i < rawAggregates.size(); i++) {
                RawAggregate agg = rawAggregates.get(i);
                double trueValue = request.aggregationType() == AggregationType.COUNT
                        ? agg.count()
                        : agg.sum();
                long groupSeed = mixSeed(request.seed(), i);
                double noise = mechanism.noise(sensitivity, epsilon, groupSeed);
                double noisy = round6(trueValue + noise);
                results.add(new GroupResult(agg.groupKey(), noisy, round6(scale)));
                log.info("[NOISE] domain={} group={} groupIndex={} seed={} scale={} noise={} noisyValue={} "
                                + "判定=原始值不记录到返回体",
                        domain, agg.groupKey(), i, groupSeed, round6(scale), round6(noise), noisy);
            }

            // 6) 提交预算。
            budgetManager.commit(domain, epsilon);
            committed = true;
            BudgetSnapshot afterCommit = budgetManager.snapshot(domain);
            log.info("[BUDGET-COMMIT] domain={} spent={} remaining={} consumedEpsilon={} 判定=提交成功",
                    domain, afterCommit.spent(), afterCommit.remaining(), epsilon);
            return new AggregationResponse(domain, epsilon, afterCommit.remaining(), results);
        } finally {
            if (!committed) {
                // 匿名度不足或任何意外异常：原样归还预占，spent 不变（预算守恒）。
                budgetManager.rollback(domain, epsilon);
                BudgetSnapshot afterRollback = budgetManager.snapshot(domain);
                log.info("[BUDGET-ROLLBACK] domain={} spent={} remaining={} rolledBackEpsilon={} "
                                + "判定=失败路径回滚完成，spent 与请求前一致",
                        domain, afterRollback.spent(), afterRollback.remaining(), epsilon);
            }
        }
    }

    public BudgetSnapshot budget(String domain) {
        return budgetManager.snapshot(domain);
    }

    /**
     * 原始聚合：按 groupColumn 分组（空/blank 表示全域），统计每组 count 与 sum。
     * 分组键经 TreeMap 排序，保证噪声派生顺序可复现。
     */
    private List<RawAggregate> aggregate(AggregationRequest request, String domain) {
        boolean grouped = request.groupColumn() != null && !request.groupColumn().isBlank();
        Map<String, long[]> countHolder = new TreeMap<>();
        Map<String, Double> sumHolder = new TreeMap<>();
        for (DataRow row : repository.findByDomain(domain)) {
            String key = grouped ? row.group() : WHOLE_DOMAIN_KEY;
            countHolder.computeIfAbsent(key, k -> new long[1])[0]++;
            sumHolder.merge(key, row.amount(), Double::sum);
        }
        List<RawAggregate> result = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : countHolder.entrySet()) {
            result.add(new RawAggregate(entry.getKey(), entry.getValue()[0],
                    sumHolder.getOrDefault(entry.getKey(), 0.0)));
        }
        return result;
    }

    /**
     * 将 (请求种子, 分组序号) 混合为该分组的确定性种子。
     * 使用与 java.util.StringLatin1 无关的简单 64 位混合（SplitMix64 常数）。
     */
    private static long mixSeed(long seed, int index) {
        long z = seed + ((long) index * 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
