package com.github.h3yow.privacybudgetqueryenginejava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** 可手动推进的时钟，用于测试空闲超时与回收。 */
class MutableClock extends Clock {

    private final AtomicLong millis;

    MutableClock(long initialMillis) {
        this.millis = new AtomicLong(initialMillis);
    }

    void advance(long deltaMillis) {
        millis.addAndGet(deltaMillis);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
