package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.InvalidPrivacyParameterException;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudget;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionBudgetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会话额度入口：申请与主动结束。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionBudgetManager manager;

    public SessionController(SessionBudgetManager manager) {
        this.manager = manager;
    }

    /** 申请一段会话额度；额度越界或超过可用预算时返回可区分的拒绝。 */
    @PostMapping
    public SessionGrantResponse apply(@RequestBody SessionGrantRequest request) {
        log.info("收到会话额度申请: domain={}, epsilon={}, idleTimeoutMs={}",
                request.domain(), request.epsilon(), request.idleTimeoutMillis());
        try {
            SessionBudget session = request.idleTimeoutMillis() == null
                    ? manager.apply(request.domain(), request.epsilon())
                    : manager.apply(request.domain(), request.epsilon(), request.idleTimeoutMillis());
            return SessionGrantResponse.granted(session.id(), session.domain(), session.granted());
        } catch (InvalidPrivacyParameterException | IllegalArgumentException e) {
            log.info("会话额度申请拒绝: domain={}, epsilon={}, 原因={}",
                    request.domain(), request.epsilon(), e.getMessage());
            return SessionGrantResponse.rejected(e.getMessage());
        }
    }

    /** 主动结束会话，归还剩余额度；重复结束幂等。 */
    @DeleteMapping("/{sessionId}")
    public SessionGrantResponse close(@PathVariable String sessionId) {
        log.info("收到会话结束请求: sessionId={}", sessionId);
        SessionBudget session = manager.find(sessionId);
        if (session == null) {
            log.info("会话结束拒绝: sessionId={}, 原因=会话不存在或已回收", sessionId);
            return SessionGrantResponse.rejected("会话不存在或已回收: " + sessionId);
        }
        double returned = session.remaining();
        manager.close(sessionId);
        return SessionGrantResponse.closed(sessionId, session.domain(), returned);
    }
}
