package com.github.h3yow.privacybudgetqueryenginejava.session;

import com.github.h3yow.privacybudgetqueryenginejava.query.QueryStatus;

/** 会话申请结果：复用查询侧的可区分拒绝状态词汇。 */
public record CreateSessionResponse(
        QueryStatus status,
        String sessionId,
        SessionInfo session,
        String reason) {

    public static CreateSessionResponse ok(BudgetSession session) {
        return new CreateSessionResponse(QueryStatus.OK, session.id(), session.info(), "OK");
    }

    public static CreateSessionResponse rejected(QueryStatus status, String reason) {
        return new CreateSessionResponse(status, null, null, reason);
    }
}
