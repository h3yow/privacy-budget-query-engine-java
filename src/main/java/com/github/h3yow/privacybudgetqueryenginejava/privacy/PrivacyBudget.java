package com.github.h3yow.privacybudgetqueryenginejava.privacy;

/**
 * 单个数据域的隐私预算账本。
 *
 * <p>并发语义：所有状态变更都在内部监视器上完成，
 * {@link #tryConsume(double)} 是"检查余额 + 扣减"的原子操作，
 * 因此同一数据域的并发请求不会超支。
 *
 * <p>守恒语义：{@code remaining() == total() - consumed()} 恒成立；
 * 失败路径通过 {@link #refund(double)} 回滚，保证预算不丢不重。
 */
public class PrivacyBudget {

    private final String domain;
    private final double totalEpsilon;
    /** 已消耗（未回滚）的预算。 */
    private double consumed;

    public PrivacyBudget(String domain, double totalEpsilon) {
        this.domain = domain;
        this.totalEpsilon = totalEpsilon;
        this.consumed = 0.0;
    }

    /** 尝试原子扣减预算；余额不足时返回 false 且状态不变。 */
    public synchronized boolean tryConsume(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("扣减量必须为正: " + epsilon);
        }
        if (consumed + epsilon > totalEpsilon) {
            return false;
        }
        consumed += epsilon;
        return true;
    }

    /** 回滚（返还）已扣减的预算，用于失败路径的守恒恢复。 */
    public synchronized void refund(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("回滚量必须为正: " + epsilon);
        }
        if (epsilon > consumed) {
            throw new IllegalStateException(
                    "回滚量 " + epsilon + " 超过已消耗量 " + consumed + "，账本不守恒");
        }
        consumed -= epsilon;
    }

    public synchronized double remaining() {
        return totalEpsilon - consumed;
    }

    public synchronized double consumed() {
        return consumed;
    }

    public double total() {
        return totalEpsilon;
    }

    public String domain() {
        return domain;
    }
}
