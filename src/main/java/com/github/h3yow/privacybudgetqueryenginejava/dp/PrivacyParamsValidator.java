package com.github.h3yow.privacybudgetqueryenginejava.dp;

import com.github.h3yow.privacybudgetqueryenginejava.exception.PrivacyQueryException;
import com.github.h3yow.privacybudgetqueryenginejava.exception.RejectionReason;
import com.github.h3yow.privacybudgetqueryenginejava.model.PrivacyParams;
import org.springframework.stereotype.Component;

/**
 * 隐私参数与请求附属参数的语义校验。
 *
 * <p>所有入口（HTTP / Service / 单测）共用本校验器，拒绝原因通过
 * {@link RejectionReason} 区分，不会在校验阶段触碰任何预算账户。
 */
@Component
public class PrivacyParamsValidator {

    /**
     * @param params       请求声明的 (epsilon, delta)
     * @param sensitivity  SUM 的 L1 灵敏度上界；COUNT 固定传 1
     * @param minGroupSize 最小匿名度
     */
    public void validate(PrivacyParams params, double sensitivity, int minGroupSize) {
        if (params == null) {
            throw new PrivacyQueryException(RejectionReason.INVALID_EPSILON,
                    "隐私参数缺失：必须声明 privacy.epsilon");
        }
        double epsilon = params.epsilon();
        if (Double.isNaN(epsilon) || Double.isInfinite(epsilon)
                || epsilon <= 0.0 || epsilon > PrivacyParams.MAX_EPSILON) {
            throw new PrivacyQueryException(RejectionReason.INVALID_EPSILON,
                    String.format("epsilon 越界：要求 0 < epsilon <= %s，实际 = %s",
                            PrivacyParams.MAX_EPSILON, epsilon));
        }
        // 本实现仅支持纯 DP（拉普拉斯机制），delta 必须为 0。
        if (Double.isNaN(params.delta()) || params.delta() < 0.0
                || Math.abs(params.delta()) > 1e-12) {
            throw new PrivacyQueryException(RejectionReason.INVALID_DELTA,
                    String.format("delta 越界：当前仅支持纯 DP，要求 delta = 0，实际 = %s",
                            params.delta()));
        }
        if (Double.isNaN(sensitivity) || Double.isInfinite(sensitivity)
                || sensitivity <= 0.0) {
            throw new PrivacyQueryException(RejectionReason.INVALID_SENSITIVITY,
                    String.format("灵敏度越界：要求 sensitivity > 0 且有限，实际 = %s",
                            sensitivity));
        }
        if (minGroupSize < 1) {
            throw new PrivacyQueryException(RejectionReason.INVALID_GROUP_SIZE,
                    String.format("最小匿名度越界：要求 minGroupSize >= 1，实际 = %s",
                            minGroupSize));
        }
    }
}
