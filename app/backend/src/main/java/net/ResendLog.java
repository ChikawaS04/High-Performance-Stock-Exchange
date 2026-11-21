package net;

import event.ExecutionHistory;

/**
 * The INFO line of a completed RESEND (P16-12, P16-9 finding 2). Pure, so its format is pinned by a test:
 * besides the frames sent it says how much of the outbound recording the linear read went through,
 * which is the cost a full retained window puts on every reconnecting bot.
 */
final class ResendLog {

    private ResendLog() {
    }

    /**
     * {@code RESEND fromSeq N to M: C frames to <addr> in T ms (read E events, B bytes from position P)}.
     */
    static String complete(long fromSeq, ExecutionHistory.Result result, Object remote, long millis) {
        StringBuilder sb = new StringBuilder(160)
                .append("RESEND fromSeq ").append(fromSeq).append(" to ").append(result.lastSeq()).append(": ")
                .append(result.count()).append(" frames to ").append(remote).append(" in ").append(millis).append(" ms");
        if (result.startPosition() >= 0) {
            sb.append(" (read ").append(result.scanned()).append(" events, ").append(result.bytesRead())
                    .append(" bytes from position ").append(result.startPosition()).append(')');
        }
        return sb.toString();
    }
}
