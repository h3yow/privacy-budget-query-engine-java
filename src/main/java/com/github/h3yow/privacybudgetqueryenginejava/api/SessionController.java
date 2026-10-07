package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.session.CreateSessionResponse;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionInfo;
import com.github.h3yow.privacybudgetqueryenginejava.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会话额度 REST 入口：申请、查看、主动结束。 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionManager sessionManager;

    public SessionController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @PostMapping
    public CreateSessionResponse create(@RequestBody CreateSessionRequest request) {
        log.info("收到会话申请: domain={}, epsilon={}, idleTimeoutMillis={}",
                request.domain(), request.epsilon(), request.idleTimeoutMillis());
        return sessionManager.create(request.domain(), request.epsilon(), request.idleTimeoutMillis());
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionInfo> info(@PathVariable String sessionId) {
        SessionInfo info = sessionManager.info(sessionId);
        return info == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(info);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<SessionInfo> close(@PathVariable String sessionId) {
        SessionInfo before = sessionManager.info(sessionId);
        if (before == null) {
            return ResponseEntity.notFound().build();
        }
        sessionManager.close(sessionId);
        return ResponseEntity.ok(sessionManager.info(sessionId));
    }
}
