package net;

/**
 * Rate limit for the WARN a RESEND logs when it asks for events journal retention has removed
 * (P16-7, R5), in the {@code market.IgnitionFailureLog} pattern. On a public site any client can ask
 * for seq 1 on every connect, so the line is logged the first time, then at most once every
 * {@value #WARN_INTERVAL_MS} ms with the count of requests not logged since. Decides what to log; the
 * caller logs it. Used by the resend thread only.
 */
final class ResendRangeLog {

    static final long WARN_INTERVAL_MS = 60_000L;

    private long lastWarnMs;
    private long suppressed;
    private boolean warned;

    /**
     * Records one request below the earliest available seq at {@code nowMs}.
     *
     * @return the WARN line to log, or null to stay quiet
     */
    String onBelowEarliest(long nowMs, long fromSeq, long firstSeq, Object client) {
        if (!warned || nowMs - lastWarnMs >= WARN_INTERVAL_MS) {
            String line = "RESEND fromSeq " + fromSeq + " from " + client + " asks for events journal retention has"
                    + " removed; resending from the earliest available seq " + firstSeq + " (" + suppressed
                    + " such request(s) not logged since the last line)";
            warned = true;
            lastWarnMs = nowMs;
            suppressed = 0;
            return line;
        }
        suppressed++;
        return null;
    }
}
