package net;

/**
 * One token bucket's parameters (P16-9), as the generic cell rate algorithm: a frame conforms when it arrives
 * no earlier than the bucket's theoretical arrival time minus its tolerance. The state is a single {@code long}
 * (the theoretical arrival time, in nanoseconds) kept by the owner of the bucket, so checking and spending a
 * token is arithmetic on primitives with no allocation.
 *
 * <p>{@code rate} frames a second sustained, and up to {@code burst} frames at once from a full bucket. A new
 * bucket is full when its state is the current time. Times come from {@link System#nanoTime()} or a test
 * clock; differences are taken so that a negative or wrapping origin is harmless.
 */
final class Gcra {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Nanoseconds one frame costs at the sustained rate. */
    final long intervalNanos;
    /** How far ahead of the present the arrival time may run: (burst - 1) intervals. */
    final long toleranceNanos;

    Gcra(long ratePerSecond, long burst) {
        if (ratePerSecond < 1 || ratePerSecond > NANOS_PER_SECOND) {
            throw new IllegalArgumentException("rate must be from 1 to 1e9 a second: " + ratePerSecond);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1: " + burst);
        }
        this.intervalNanos = NANOS_PER_SECOND / ratePerSecond;
        this.toleranceNanos = (burst - 1) * intervalNanos;
    }

    /** Whether a frame at {@code now} conforms to a bucket whose arrival time is {@code tat}. */
    boolean conforms(long tat, long now) {
        return tat - now <= toleranceNanos;
    }

    /** The bucket's arrival time after spending one token at {@code now}; call only for a conforming frame. */
    long spend(long tat, long now) {
        return (tat - now > 0 ? tat : now) + intervalNanos;
    }
}
