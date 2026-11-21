package net;

import event.ExecutionHistory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The completed-RESEND INFO line (P16-12). */
class ResendLogTest {

    @Test
    void namesFramesTimeAndHowMuchOfTheRecordingWasRead() {
        ExecutionHistory.Result result = new ExecutionHistory.Result(3_004, 141, false, 2_864, 141, 22_560, 458_752);
        assertEquals("RESEND fromSeq 2864 to 3004: 141 frames to /172.30.0.10:41234 in 87 ms"
                        + " (read 141 events, 22560 bytes from position 458752)",
                ResendLog.complete(2_864, result, "/172.30.0.10:41234", 87));
    }

    @Test
    void aCatchUpFromTheTailStillReportsTheWholeLinearRead() {
        ExecutionHistory.Result result = new ExecutionHistory.Result(3_493, 0, false, 1, 3_493, 490_624, 0);
        assertEquals("RESEND fromSeq 3494 to 3493: 0 frames to peer in 16 ms (read 3493 events, 490624 bytes from"
                + " position 0)", ResendLog.complete(3_494, result, "peer", 16));
    }

    @Test
    void aResultWithoutReadStatisticsKeepsTheShortForm() {
        assertEquals("RESEND fromSeq 1 to 5: 5 frames to peer in 2 ms",
                ResendLog.complete(1, new ExecutionHistory.Result(5, 5, false, 1), "peer", 2));
    }
}
