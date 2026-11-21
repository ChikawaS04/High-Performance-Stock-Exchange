package participant;

/**
 * A bot's back-off while the venue refuses its NEW orders (P16-9, P16-8 finding 2). While the disk guard is in
 * STOP the venue answers each NEW with a seq-less ORDER_REJECTED, and the maker alone would requote 20 refused
 * NEWs a second. After {@link #TRIGGER} consecutive venue refusals of this bot's own orders, the bot stops
 * ticking its strategy for a pause that starts at {@link #START_MILLIS} and doubles with each pause that ends
 * with the venue still refusing, up to the configured cap ({@code BOTS_REJECT_BACKOFF_MAX_MS}).
 *
 * <p>When a pause ends the client reads {@code GET /health} once ({@link #takeProbe}): if the disk state is not
 * STOP (OK or LOW, or the venue has no disk guard), ticking resumes ({@link #onProbe}); otherwise, or if the
 * venue cannot be read, the next, longer pause starts. Resuming on "not STOP" rather than "OK" is deliberate:
 * the hard stop is left into LOW, where NEW is accepted again. A resumed bot keeps its pause length, so a stop
 * that is still flapping backs off further; the first own ORDER_ACCEPTED ({@link #onAccepted}) resets
 * everything.
 *
 * <p>Only the venue's refusals count. The bot's own risk gate refusals are synthetic rejects handed straight to
 * the strategy and never pass through here. Reconnecting, RESEND and the cancels sent on a session close, at a
 * resync or at stop are unaffected: only the strategy's tick is held.
 *
 * <p>Pure: no clock, no I/O; the caller passes wall-clock millis. Confined to the client's executor thread.
 */
final class RejectBackoff {

    /** Consecutive venue refusals that start a pause. */
    static final int TRIGGER = 3;
    /** The first pause. */
    static final long START_MILLIS = 1_000;
    /** The default cap on a pause ({@code BOTS_REJECT_BACKOFF_MAX_MS}). */
    static final long DEFAULT_MAX_MILLIS = 30_000;

    private final long maxMillis;

    private int streak;
    /** A pause is due at the next tick (refusals arrive without a clock). */
    private boolean pauseDue;
    private boolean paused;
    private long pausedUntil;
    /** The length of the current or last pause; 0 when none since the last reset. */
    private long delayMillis;
    /** A /health read is wanted, or in flight. */
    private boolean probeWanted;
    private boolean probing;
    /** Set when a pause episode starts, for the caller's one WARN. */
    private boolean episodeStarted;
    private long refusals;

    RejectBackoff(long maxMillis) {
        if (maxMillis < START_MILLIS) {
            throw new IllegalArgumentException("the back-off cap must be at least " + START_MILLIS + " ms");
        }
        this.maxMillis = maxMillis;
    }

    /** A seq-less ORDER_REJECTED for one of this bot's own orders. */
    void onVenueRefusal() {
        refusals++;
        if (++streak >= TRIGGER && !paused) {
            pauseDue = true;
        }
    }

    /** An ORDER_ACCEPTED (with a seq) for one of this bot's own orders: NEW works, reset everything. */
    void onAccepted() {
        streak = 0;
        pauseDue = false;
        paused = false;
        delayMillis = 0;
        probeWanted = false;
    }

    /**
     * Whether the strategy's tick is held at {@code nowMillis}. Starts a due pause, and asks for a probe when a
     * pause has run out.
     */
    boolean holdsTick(long nowMillis) {
        if (pauseDue) {
            pauseDue = false;
            if (delayMillis == 0) {
                episodeStarted = true;
            }
            startPause(nowMillis);
            return true;
        }
        if (!paused) {
            return false;
        }
        if (nowMillis - pausedUntil >= 0 && !probing) {
            probeWanted = true;
        }
        return true;
    }

    private void startPause(long nowMillis) {
        delayMillis = delayMillis == 0 ? START_MILLIS : Math.min(delayMillis * 2, maxMillis);
        paused = true;
        pausedUntil = nowMillis + delayMillis;
    }

    /** True once when a /health read is due; the caller then reads it and reports with {@link #onProbe}. */
    boolean takeProbe() {
        if (!probeWanted) return false;
        probeWanted = false;
        probing = true;
        return true;
    }

    /**
     * What the /health read showed.
     *
     * @param stopped true when the disk state is STOP or the venue could not be read
     * @return true when ticking resumes
     */
    boolean onProbe(boolean stopped, long nowMillis) {
        probing = false;
        if (!paused) {
            return false;   // an accepted order reset everything meanwhile
        }
        if (stopped) {
            startPause(nowMillis);
            return false;
        }
        paused = false;
        streak = 0;
        return true;
    }

    /** True once after a new pause episode began (no pause since the last reset). */
    boolean takeEpisodeStarted() {
        boolean v = episodeStarted;
        episodeStarted = false;
        return v;
    }

    boolean paused() {
        return paused;
    }

    long delayMillis() {
        return delayMillis;
    }

    /** Venue refusals of this bot's NEW orders so far. */
    long refusals() {
        return refusals;
    }

    long maxMillis() {
        return maxMillis;
    }
}
