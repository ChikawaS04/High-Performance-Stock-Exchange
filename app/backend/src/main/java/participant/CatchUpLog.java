package participant;

/**
 * The INFO line a bot logs when a reconnect's RESEND has caught it up (P16-12). Pure, so the format is
 * pinned by a test. The catch-up time runs from the first RESEND of this catch-up to the
 * {@code RESEND_COMPLETE} that closed the gap; the offline time runs from the lost connection to the same
 * moment, so it includes the reconnect back-off and the venue's recovery.
 */
final class CatchUpLog {

    private CatchUpLog() {
    }

    /**
     * {@code <bot> caught up through seq N in T ms (RESEND from seq S, k request(s), offline D ms); trading
     * resumes}; the offline part is left out when {@code offlineMillis} is negative (not known).
     */
    static String caughtUp(String bot, long lastApplied, long catchUpMillis, long resendFromSeq, int requests,
                           long offlineMillis) {
        StringBuilder sb = new StringBuilder(128)
                .append(bot).append(" caught up through seq ").append(lastApplied).append(" in ").append(catchUpMillis)
                .append(" ms (RESEND from seq ").append(resendFromSeq).append(", ").append(requests)
                .append(" request(s)");
        if (offlineMillis >= 0) sb.append(", offline ").append(offlineMillis).append(" ms");
        return sb.append("); trading resumes").toString();
    }
}
