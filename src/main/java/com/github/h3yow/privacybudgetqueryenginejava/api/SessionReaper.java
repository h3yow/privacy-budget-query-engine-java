package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.session.SessionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时回收空闲超时的会话，把未用额度归还给数据域，避免死额度长期占用。 */
@Component
public class SessionReaper {

    private final SessionManager sessionManager;

    public SessionReaper(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Scheduled(fixedDelay = 1_000L)
    public void reap() {
        sessionManager.reapExpired();
    }
}
