package session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock a test moves by hand, readable from any thread. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(String instant) {
        this.now = Instant.parse(instant);
    }

    public void set(String instant) {
        now = Instant.parse(instant);
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock self = this;
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId other) { return self.withZone(other); }
            @Override public Instant instant() { return self.instant(); }
        };
    }

    @Override
    public Instant instant() {
        return now;
    }
}
