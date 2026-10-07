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
 *
 * <p>会话额度语义：{@code reserved} 记录被会话占着但尚未真正花掉的预算，
 * 恒有 {@code consumed + reserved + available == total}。会话扣费通过
 * {@link #settle(double)} 把占用转为实耗，结束/过期通过 {@link #release(double)}
 * 把未用占用归还为可用。
 */
public class PrivacyBudget {

    private final String domain;
    private final double totalEpsilon;
    /** 已消耗（未回滚）的预算。 */
    private double consumed;
    /** 被会话占着、尚未真正花掉的预算。 */
    private double reserved;

    public PrivacyBudget(String domain, double totalEpsilon) {
        this.domain = domain;
        this.totalEpsilon = totalEpsilon;
        this.consumed = 0.0;
        this.reserved = 0.0;
    }

    /** 尝试原子扣减预算；可用余额（扣除占用后）不足时返回 false 且状态不变。 */
    public synchronized boolean tryConsume(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("扣减量必须为正: " + epsilon);
        }
        if (consumed + reserved + epsilon > totalEpsilon) {
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

    /** 申请会话额度：原子地把可用预算转为占用；可用不足时返回 false 且状态不变。 */
    public synchronized boolean tryReserve(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("占用量必须为正: " + epsilon);
        }
        if (consumed + reserved + epsilon > totalEpsilon) {
            return false;
        }
        reserved += epsilon;
        return true;
    }

    /** 会话实际消费：把一笔占用转为实耗（调用方必须确实持有该占用）。 */
    public synchronized void settle(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("结算量必须为正: " + epsilon);
        }
        if (epsilon > reserved) {
            throw new IllegalStateException(
                    "结算量 " + epsilon + " 超过占用量 " + reserved + "，账本不守恒");
        }
        reserved -= epsilon;
        consumed += epsilon;
    }

    /** 会话消费回滚：把一笔实耗退回为占用（扰动阶段异常路径）。 */
    public synchronized void unsettle(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("回退量必须为正: " + epsilon);
        }
        if (epsilon > consumed) {
            throw new IllegalStateException(
                    "回退量 " + epsilon + " 超过已消耗量 " + consumed + "，账本不守恒");
        }
        consumed -= epsilon;
        reserved += epsilon;
    }

    /** 会话结束/过期：把未用的占用归还为可用；不重复归还由会话状态机保证。 */
    public synchronized void release(double epsilon) {
        if (epsilon < 0) {
            throw new IllegalArgumentException("归还量不得为负: " + epsilon);
        }
        if (epsilon > reserved) {
            throw new IllegalStateException(
                    "归还量 " + epsilon + " 超过占用量 " + reserved + "，账本不守恒");
        }
        reserved -= epsilon;
    }

    /** 未真正花掉的预算（含被会话占着的部分），保持原有语义兼容。 */
    public synchronized double remaining() {
        return totalEpsilon - consumed;
    }

    /** 真正可立即动用的预算：扣除实耗与占用。 */
    public synchronized double available() {
        return totalEpsilon - consumed - reserved;
    }

    public synchronized double reserved() {
        return reserved;
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
