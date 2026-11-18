package market;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A wall clock a test moves by hand (P16-3). Safe to read from any thread. */
final class MutableClock extends Clock {

    private volatile long millis;

    MutableClock(Instant start) {
        this.millis = start.toEpochMilli();
    }

    void set(Instant t) {
        millis = t.toEpochMilli();
    }

    void advanceMillis(long ms) {
        millis += ms;
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
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
