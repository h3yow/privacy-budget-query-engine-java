package com.github.h3yow.privacybudgetqueryenginejava.session;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;

/**
 * 会话额度：调用方为某数据域一次性申请的一段可重复使用的预算。
 *
 * <p>语义：申请时从数据域账本"预留"（reserved）对应额度；会话内的查询
 * 从该额度中扣减（reserved -&gt; consumed）；主动结束、空闲超时或异常断开时，
 * 未花完的部分自动归还账本（reserved -&gt; available），同一数据域的其他请求
 * 立即可用。
 *
 * <p>并发语义：所有状态变更在会话内部监视器上完成，{@link #tryConsume}
 * 是"校验状态 + 扣减"的原子操作，同一会话的并发消费不会重复花，
 * 累计消耗不超过申请量。
 *
 * <p>幂等语义：{@link #close()} 只生效一次，重复关闭/超时回收/主动结束
 * 并发发生时不会重复归还，账目不丢不重。
 */
public class SessionBudget {

    /** 单次消费尝试的结果。 */
    public enum ConsumeOutcome {
        /** 扣减成功。 */
        OK,
        /** 会话内剩余额度不足。 */
        INSUFFICIENT,
        /** 会话已空闲超时（本次访问触发了自动回收）。 */
        EXPIRED,
        /** 会话已被主动结束。 */
        CLOSED
    }

    private final String id;
    private final String domain;
    private final double granted;
    private final long idleTimeoutMillis;
    private final PrivacyBudget domainBudget;

    private double consumed;
    private long lastAccessMillis;
    private boolean closed;

    SessionBudget(String id, String domain, double granted, long idleTimeoutMillis,
                  PrivacyBudget domainBudget, long nowMillis) {
        this.id = id;
        this.domain = domain;
        this.granted = granted;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.domainBudget = domainBudget;
        this.consumed = 0.0;
        this.lastAccessMillis = nowMillis;
        this.closed = false;
    }

    /**
     * 尝试从会话额度中扣减；原子完成"校验状态 + 扣减"。
     * 发现已空闲超时时先自动回收再返回 {@link ConsumeOutcome#EXPIRED}。
     */
    public synchronized ConsumeOutcome tryConsume(double epsilon, long nowMillis) {
        if (closed) {
            return ConsumeOutcome.CLOSED;
        }
        if (isIdleExpired(nowMillis)) {
            closeLocked();
            return ConsumeOutcome.EXPIRED;
        }
        lastAccessMillis = nowMillis;
        if (epsilon > granted - consumed) {
            return ConsumeOutcome.INSUFFICIENT;
        }
        consumed += epsilon;
        domainBudget.commitReserved(epsilon);
        return ConsumeOutcome.OK;
    }

    /**
     * 查询处理异常时回滚本次扣减。
     * 会话仍存活：退回会话内（consumed -&gt; reserved）；
     * 会话已被并发关闭：直接退回数据域可用余额（consumed -&gt; available）。
     */
    public synchronized void refund(double epsilon) {
        if (epsilon <= 0 || epsilon > consumed) {
            throw new IllegalStateException(
                    "会话回滚量 " + epsilon + " 非法（已消耗 " + consumed + "），账目不守恒");
        }
        consumed -= epsilon;
        if (closed) {
            domainBudget.refund(epsilon);
        } else {
            domainBudget.uncommitReserved(epsilon);
        }
    }

    /** 若已空闲超时则自动结束并归还剩余额度；返回本次是否发生了回收。 */
    public synchronized boolean expireIfIdle(long nowMillis) {
        if (closed || !isIdleExpired(nowMillis)) {
            return false;
        }
        closeLocked();
        return true;
    }

    /** 主动结束会话，归还剩余额度；幂等，重复调用返回 0 且不会重复归还。 */
    public synchronized double close() {
        if (closed) {
            return 0.0;
        }
        return closeLocked();
    }

    /** 会话内剩余可用额度。 */
    public synchronized double remaining() {
        return closed ? 0.0 : granted - consumed;
    }

    public String id() {
        return id;
    }

    public String domain() {
        return domain;
    }

    public double granted() {
        return granted;
    }

    public synchronized double consumed() {
        return consumed;
    }

    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private boolean isIdleExpired(long nowMillis) {
        return nowMillis - lastAccessMillis >= idleTimeoutMillis;
    }

    /** 调用方必须已持有会话监视器。 */
    private double closeLocked() {
        closed = true;
        double unused = granted - consumed;
        domainBudget.releaseReservation(unused);
        return unused;
    }
}
