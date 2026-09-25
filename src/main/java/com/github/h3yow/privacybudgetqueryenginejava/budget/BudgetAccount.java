package com.github.h3yow.privacybudgetqueryenginejava.budget;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个数据域的隐私预算账户。
 *
 * <p>采用“预占（reserve）/ 提交（commit，空操作）/ 回滚（rollback）”三阶段：
 * <ul>
 *   <li>reserve 在锁内完成“余额检查 + 扣减”，保证同域并发请求不会超支；</li>
 *   <li>匿名度校验失败等路径必须调用 rollback 把预占额原样归还；</li>
 *   <li>成功路径 commit 后预占额才成为永久消耗。</li>
 * </ul>
 * spent 始终表示“已永久提交的消耗”，reserved 表示已预占未决的消耗，
 * 因此任意时刻 spent + reserved + remaining = limit（预算守恒）。
 */
public class BudgetAccount {

    private final double limit;

    /** 已提交的累计消耗。 */
    private final AtomicReference<Double> spent = new AtomicReference<>(0.0);

    /** 已预占但尚未提交/回滚的消耗。 */
    private final AtomicReference<Double> reserved = new AtomicReference<>(0.0);

    public BudgetAccount(double limit) {
        if (limit <= 0 || Double.isNaN(limit) || Double.isInfinite(limit)) {
            throw new IllegalArgumentException("预算上界必须为正有限值: " + limit);
        }
        this.limit = limit;
    }

    public double getLimit() {
        return limit;
    }

    public double getSpent() {
        return spent.get();
    }

    public double getReserved() {
        return reserved.get();
    }

    public double getRemaining() {
        return limit - spent.get() - reserved.get();
    }

    /**
     * 原子预占：余额不足时不修改任何状态并返回 false。
     */
    public synchronized boolean reserve(double epsilon) {
        if (epsilon <= 0 || Double.isNaN(epsilon) || Double.isInfinite(epsilon)) {
            throw new IllegalArgumentException("预占额必须为正有限值: " + epsilon);
        }
        if (spent.get() + reserved.get() + epsilon > limit + 1e-12) {
            return false;
        }
        reserved.updateAndGet(v -> v + epsilon);
        return true;
    }

    /**
     * 提交预占：预占额转为永久消耗。
     */
    public synchronized void commit(double epsilon) {
        double current = reserved.get();
        if (epsilon - current > 1e-9) {
            throw new IllegalStateException(
                    String.format("提交额 %.12f 超过预占额 %.12f", epsilon, current));
        }
        reserved.set(current - epsilon);
        spent.updateAndGet(v -> v + epsilon);
    }

    /**
     * 回滚预占：把预占额原样归还，spent 不变。
     */
    public synchronized void rollback(double epsilon) {
        double current = reserved.get();
        if (epsilon - current > 1e-9) {
            throw new IllegalStateException(
                    String.format("回滚额 %.12f 超过预占额 %.12f", epsilon, current));
        }
        reserved.set(current - epsilon);
    }
}
