package com.github.h3yow.privacybudgetqueryenginejava.session;

/** 会话额度生命周期状态。 */
public enum SessionState {
    /** 活跃，可继续扣费。 */
    ACTIVE,
    /** 调用方主动结束，未用额度已归还。 */
    CLOSED,
    /** 空闲超时被动回收，未用额度已归还。 */
    EXPIRED
}
