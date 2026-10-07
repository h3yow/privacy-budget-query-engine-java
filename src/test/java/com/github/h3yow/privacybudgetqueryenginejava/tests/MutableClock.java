package com.github.h3yow.privacybudgetqueryenginejava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 测试用可控时钟：可手动推进时间，驱动会话空闲超时。 */
final class MutableClock extends Clock {

    private long millis;

    MutableClock(long startMillis) {
        this.millis = startMillis;
    }

    void advance(long deltaMillis) {
        millis += deltaMillis;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
