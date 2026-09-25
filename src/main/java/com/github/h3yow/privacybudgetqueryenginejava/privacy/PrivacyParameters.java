package com.github.h3yow.privacybudgetqueryenginejava.privacy;

/**
 * 请求声明的隐私参数（epsilon, delta）。
 *
 * <p>语义约定：
 * <ul>
 *   <li>epsilon：单次查询消耗的隐私预算，必须在 (0, MAX_EPSILON] 区间内；</li>
 *   <li>delta：近似差分隐私的松弛项，必须在 [0, MAX_DELTA] 区间内。</li>
 * </ul>
 */
public record PrivacyParameters(double epsilon, double delta) {

    /** 单次请求允许的最大 epsilon，防止一次耗尽全部预算。 */
    public static final double MAX_EPSILON = 10.0;
    /** delta 允许的最大值。 */
    public static final double MAX_DELTA = 1e-3;

    /** 校验参数是否合法；不合法时抛出 {@link InvalidPrivacyParameterException}。 */
    public void validate() {
        if (Double.isNaN(epsilon) || Double.isInfinite(epsilon) || epsilon <= 0 || epsilon > MAX_EPSILON) {
            throw new InvalidPrivacyParameterException(
                    "epsilon 必须在 (0, " + MAX_EPSILON + "] 区间内，实际值: " + epsilon);
        }
        if (Double.isNaN(delta) || delta < 0 || delta > MAX_DELTA) {
            throw new InvalidPrivacyParameterException(
                    "delta 必须在 [0, " + MAX_DELTA + "] 区间内，实际值: " + delta);
        }
    }
}
