package com.github.h3yow.privacybudgetqueryenginejava.session;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;

import java.time.Clock;

/**
 * 一段可重复使用的会话额度。
 *
 * <p>并发语义：所有状态变更在本对象监视器上完成，账本侧操作委托给
 * 已同步的 {@link PrivacyBudget}；锁顺序恒为 session → budget，不会死锁。
 * 同一会话被并发使用时，"检查余额 + 扣减"是原子操作，累计消耗不会超过申请量。
 *
 * <p>生命周期：ACTIVE → CLOSED（主动结束）或 EXPIRED（空闲超时）。
 * 状态迁移只发生一次，未用额度随之归还，因此不会重复归还、也不会留下死额度。
 */
public class BudgetSession {

    private final String id;
    private final String domain;
    private final double granted;
    private final PrivacyBudget budget;
    private final Clock clock;
    private final long idleTimeoutMillis;

    private SessionState state = SessionState.ACTIVE;
    /** 会话内已真正花掉的额度。 */
    private double consumed;
    private long lastTouchedAt;

    public BudgetSession(String id, String domain, double grantedEpsilon,
                         PrivacyBudget budget, Clock clock, long idleTimeoutMillis) {
        this.id = id;
        this.domain = domain;
        this.granted = grantedEpsilon;
        this.budget = budget;
        this.clock = clock;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.lastTouchedAt = clock.millis();
    }

    public String id() {
        return id;
    }

    public String domain() {
        return domain;
    }

    public synchronized SessionState state() {
        return state;
    }

    /**
     * 尝试从会话额度中扣费：原子地把一笔占用转为实耗。
     * 会话已失效（含此处发现空闲超时）或余额不足时返回 false，状态不变。
     */
    public boolean tryConsume(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("扣减量必须为正: " + epsilon);
        }
        synchronized (this) {
            expireIfIdleLocked();
            if (state != SessionState.ACTIVE) {
                return false;
            }
            if (consumed + epsilon > granted) {
                return false;
            }
            consumed += epsilon;
            budget.settle(epsilon);
            lastTouchedAt = clock.millis();
            return true;
        }
    }

    /** 回滚一次已扣未结的额度（扰动阶段异常路径）：实耗退回为占用。 */
    public void refund(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("回滚量必须为正: " + epsilon);
        }
        synchronized (this) {
            if (epsilon > consumed) {
                throw new IllegalStateException(
                        "回滚量 " + epsilon + " 超过会话已消耗量 " + consumed + "，账本不守恒");
            }
            consumed -= epsilon;
            budget.unsettle(epsilon);
        }
    }

    /** 主动结束：归还剩余额度；幂等，重复调用返回 false。 */
    public boolean close() {
        synchronized (this) {
            if (state != SessionState.ACTIVE) {
                return false;
            }
            transitionTo(SessionState.CLOSED);
            return true;
        }
    }

    /** 若已空闲超时则回收；返回是否发生了状态迁移。 */
    public boolean expireIfIdle() {
        synchronized (this) {
            return expireIfIdleLocked();
        }
    }

    private boolean expireIfIdleLocked() {
        if (state != SessionState.ACTIVE) {
            return false;
        }
        if (clock.millis() - lastTouchedAt < idleTimeoutMillis) {
            return false;
        }
        transitionTo(SessionState.EXPIRED);
        return true;
    }

    /** 状态迁移 + 未用额度归还，只在 ACTIVE 下发生一次，保证不重复归还。 */
    private void transitionTo(SessionState target) {
        double unused = granted - consumed;
        state = target;
        if (unused > 0) {
            budget.release(unused);
        }
    }

    /** 当前仍被该会话占着、可继续花费的额度。 */
    public synchronized double held() {
        return granted - consumed;
    }

    public synchronized SessionInfo info() {
        return new SessionInfo(id, domain, state, granted, consumed,
                granted - consumed, lastTouchedAt + idleTimeoutMillis);
    }
}
