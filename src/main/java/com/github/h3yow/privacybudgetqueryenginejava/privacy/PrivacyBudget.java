package com.github.h3yow.privacybudgetqueryenginejava.privacy;

/**
 * 单个数据域的隐私预算账本。
 *
 * <p>并发语义：所有状态变更都在内部监视器上完成，
 * {@link #tryConsume(double)} 是"检查余额 + 扣减"的原子操作，
 * 因此同一数据域的并发请求不会超支。
 *
 * <p>守恒语义：{@code remaining() == total() - consumed() - reserved()} 恒成立，
 * 即"已花掉的 + 被占着的 + 还能用的 == 总额"；
 * 失败路径通过 {@link #refund(double)} / {@link #uncommitReserved(double)} 回滚，
 * 会话结束通过 {@link #releaseReservation(double)} 归还，保证预算不丢不重。
 */
public class PrivacyBudget {

    private final String domain;
    private final double totalEpsilon;
    /** 已消耗（未回滚）的预算。 */
    private double consumed;
    /** 被会话额度占着但尚未真正花掉的预算。 */
    private double reserved;

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

    /** 为会话额度预留预算；可用余额不足时返回 false 且状态不变。 */
    public synchronized boolean tryReserve(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("预留量必须为正: " + epsilon);
        }
        if (consumed + reserved + epsilon > totalEpsilon) {
            return false;
        }
        reserved += epsilon;
        return true;
    }

    /** 会话内消费：预留转实耗（reserved -&gt; consumed）。 */
    public synchronized void commitReserved(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("提交量必须为正: " + epsilon);
        }
        if (epsilon > reserved) {
            throw new IllegalStateException(
                    "提交量 " + epsilon + " 超过被占用量 " + reserved + "，账本不守恒");
        }
        reserved -= epsilon;
        consumed += epsilon;
    }

    /** 会话内回滚：实耗转回预留（consumed -&gt; reserved），用于查询异常路径。 */
    public synchronized void uncommitReserved(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("回滚量必须为正: " + epsilon);
        }
        if (epsilon > consumed) {
            throw new IllegalStateException(
                    "回滚量 " + epsilon + " 超过已消耗量 " + consumed + "，账本不守恒");
        }
        consumed -= epsilon;
        reserved += epsilon;
    }

    /** 会话结束/过期：释放未花完的预留（reserved -&gt; available）。 */
    public synchronized void releaseReservation(double epsilon) {
        if (epsilon < 0) {
            throw new IllegalArgumentException("释放量不能为负: " + epsilon);
        }
        if (epsilon > reserved) {
            throw new IllegalStateException(
                    "释放量 " + epsilon + " 超过被占用量 " + reserved + "，账本不守恒");
        }
        reserved -= epsilon;
    }

    /** 当前被会话额度占着的预算。 */
    public synchronized double reserved() {
        return reserved;
    }

    /** 原子快照：保证审计视图自身满足 consumed + reserved + available == total。 */
    public synchronized BudgetAudit audit() {
        return new BudgetAudit(domain, totalEpsilon, consumed, reserved,
                totalEpsilon - consumed - reserved);
    }

    /** 可用余额：总额减去已花掉的与被占着的。 */
    public synchronized double remaining() {
        return totalEpsilon - consumed - reserved;
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
