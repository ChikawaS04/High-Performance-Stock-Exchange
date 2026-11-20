package journal;

import org.junit.jupiter.api.Test;
import recovery.RestoredSnapshot;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure tests of where journal retention may move each start (P16-7, R1 to R3). */
class RetentionPlanTest {

    private static final int SEG = 64 * 1024;
    private static final int TERM = 64 * 1024;
    private static final long IN_ID = 0L;
    private static final long OUT_ID = 1L;

    private static RetentionPlan.Stream in(long start, long recorded) {
        return new RetentionPlan.Stream(IN_ID, start, recorded, TERM, SEG);
    }

    private static RetentionPlan.Stream out(long start, long recorded) {
        return new RetentionPlan.Stream(OUT_ID, start, recorded, TERM, SEG);
    }

    private static RestoredSnapshot snap(long seq, long inPos, long outLastSeq, long outPos, int closeDate) {
        return new RestoredSnapshot(seq, inPos, IN_ID, outLastSeq, 1L, 0L, List.of(), 2, false, -1,
                closeDate > 0 ? closeDate : 20261101, new byte[8], OUT_ID, outPos, closeDate);
    }

    private static RetentionPlan.Decision decide(int days, List<RestoredSnapshot> snapshots) {
        return RetentionPlan.decide(days, in(0, 10 * SEG + 5), out(0, 10 * SEG + 5), snapshots, Long.MAX_VALUE,
                false, -1, -1);
    }

    /** Four closes, a day apart in seq, plus a newer interval snapshot. Weekend dates are absent by design. */
    private static List<RestoredSnapshot> fourCloses() {
        List<RestoredSnapshot> s = new ArrayList<>();
        s.add(snap(100, 2 * SEG + 100, 90, 2 * SEG + 50, 20261106));   // Friday
        s.add(snap(200, 4 * SEG + 100, 190, 4 * SEG + 70, 20261109));  // Monday (no weekend closes)
        s.add(snap(300, 6 * SEG + 100, 290, 6 * SEG + 80, 20261110));
        s.add(snap(400, 8 * SEG + 100, 390, 8 * SEG + 90, 20261111));
        s.add(snap(450, 9 * SEG + 10, 440, 9 * SEG + 5, -1));
        return s;
    }

    @Test
    void theWindowStartsAtTheCloseOfTheNPlusFirstMostRecentDay() {
        RetentionPlan.Decision d = decide(2, fourCloses());
        assertNull(d.skip());
        assertEquals(4, d.closesSeen());
        // The third most recent close (20261109) is the edge; the oldest snapshot (20261106) still floors.
        assertEquals(2 * SEG, d.inboundNewStart(), "the oldest usable snapshot floors the inbound start");
        assertEquals(4 * SEG, d.outboundNewStart(), "no snapshot floor on the outbound stream");
    }

    @Test
    void withTheOldestCloseSnapshotPrunedTheWindowAloneDecides() {
        List<RestoredSnapshot> s = fourCloses();
        s.remove(0);
        RetentionPlan.Decision d = decide(2, s);
        assertEquals(3, d.closesSeen());
        assertEquals(4 * SEG, d.inboundNewStart());
        assertEquals(4 * SEG, d.outboundNewStart());
    }

    @Test
    void fewerThanNPlusOneClosesPurgeNothing() {
        List<RestoredSnapshot> s = fourCloses().subList(2, 5);
        RetentionPlan.Decision d = decide(2, s);
        assertNull(d.skip());
        assertEquals(2, d.closesSeen());
        assertEquals(0, d.inboundNewStart());
        assertEquals(0, d.outboundNewStart());
    }

    @Test
    void zeroDaysStartsTheWindowAtTheLatestClose() {
        List<RestoredSnapshot> s = List.of(snap(400, 8 * SEG + 100, 390, 8 * SEG + 90, 20261111),
                snap(450, 9 * SEG + 10, 440, 9 * SEG + 5, -1));
        RetentionPlan.Decision d = decide(0, s);
        assertEquals(8 * SEG, d.inboundNewStart());
        assertEquals(8 * SEG, d.outboundNewStart());
    }

    @Test
    void aDatesCloseSnapshotIsItsLowestSeq() {
        // A deferred or repeated snapshot with the same close date, later in the stream, does not move the edge.
        List<RestoredSnapshot> s = new ArrayList<>(List.of(snap(400, 8 * SEG + 100, 390, 8 * SEG + 90, 20261111),
                snap(420, 9 * SEG + 100, 410, 9 * SEG + 90, 20261111), snap(450, 9 * SEG + 200, 440, 9 * SEG + 150, -1)));
        RetentionPlan.Decision d = decide(0, s);
        assertEquals(1, d.closesSeen());
        assertEquals(8 * SEG, d.outboundNewStart());
    }

    @Test
    void anUnknownOutboundPositionFallsBackToAnOlderClose() {
        List<RestoredSnapshot> s = fourCloses();
        s.set(1, snap(200, 4 * SEG + 100, 190, -1, 20261109));
        RetentionPlan.Decision d = decide(2, s);
        assertEquals(2 * SEG, d.outboundNewStart(), "20261106's outbound position, the next older known one");

        s.remove(0);
        d = decide(2, s);
        assertEquals(0, d.outboundNewStart(), "no older known position: the outbound start stays");
    }

    @Test
    void theReporterFloorsBothStreams() {
        RetentionPlan.Decision d = RetentionPlan.decide(2, in(0, 10 * SEG), out(0, 10 * SEG), fourCloses(),
                Long.MAX_VALUE, true, SEG + 7, 3 * SEG + 9);
        assertNull(d.skip());
        assertEquals(SEG, d.inboundNewStart());
        assertEquals(3 * SEG, d.outboundNewStart());
    }

    @Test
    void aRequiredReporterWithoutACommittedCheckpointHoldsRetention() {
        RetentionPlan.Decision d = RetentionPlan.decide(2, in(0, 10 * SEG), out(0, 10 * SEG), fourCloses(),
                Long.MAX_VALUE, true, -1, 3 * SEG);
        assertEquals(RetentionPlan.Skip.REPORTER_NOT_RUNNING, d.skip());
        assertEquals(0, d.inboundNewStart());
        assertEquals(0, d.outboundNewStart());
    }

    @Test
    void rule3LeavesOutSnapshotsTheOutboundRecordingDoesNotYetCover() {
        List<RestoredSnapshot> s = List.of(snap(400, 8 * SEG + 100, 390, 8 * SEG + 90, 20261111),
                snap(450, 9 * SEG + 10, 440, 9 * SEG + 5, -1));
        RetentionPlan.Decision d = RetentionPlan.decide(0, in(0, 10 * SEG), out(0, 10 * SEG), s, 389, false, -1, -1);
        assertEquals(RetentionPlan.Skip.NO_USABLE_SNAPSHOT, d.skip(), "neither is covered at seq 389");
        d = RetentionPlan.decide(0, in(0, 10 * SEG), out(0, 10 * SEG), s, 390, false, -1, -1);
        assertNull(d.skip());
        assertEquals(8 * SEG, d.inboundNewStart());
    }

    @Test
    void snapshotsBelowTheStartAreNotUsable() {
        List<RestoredSnapshot> s = List.of(snap(100, SEG + 3, 90, SEG, 20261106));
        RetentionPlan.Decision d = RetentionPlan.decide(0, in(2 * SEG, 10 * SEG), out(0, 10 * SEG), s,
                Long.MAX_VALUE, false, -1, -1);
        assertEquals(RetentionPlan.Skip.NO_USABLE_SNAPSHOT, d.skip());
    }

    @Test
    void theSegmentHoldingTheLastRecordedByteSurvives() {
        // Floor exactly at a recorded position that sits on a segment boundary: the last segment stays.
        RetentionPlan.Stream exact = in(0, 4 * SEG);
        assertEquals(3 * SEG, RetentionPlan.newStart(exact, 4 * SEG));
        assertEquals(3 * SEG, RetentionPlan.newStart(in(0, 3 * SEG + 1), Long.MAX_VALUE));
        assertEquals(0, RetentionPlan.newStart(in(0, SEG), SEG), "one segment: nothing can go");
    }

    @Test
    void newStartsAreSegmentBasesAndNeverMoveBack() {
        assertEquals(2 * SEG, RetentionPlan.newStart(in(0, 10 * SEG), 3 * SEG - 1));
        assertEquals(2 * SEG, RetentionPlan.newStart(in(2 * SEG, 10 * SEG), 2 * SEG + 5), "nothing to purge");
        assertEquals(2 * SEG, RetentionPlan.newStart(in(2 * SEG, 10 * SEG), SEG), "a floor below the start");
        assertEquals(0, RetentionPlan.newStart(in(0, 0), 5 * SEG), "an empty recording");
    }

    @Test
    void positionsBeyondARecordingAreASkipOnDoubt() {
        List<RestoredSnapshot> beyond = List.of(snap(100, 11 * SEG, 90, SEG, 20261106));
        assertEquals(RetentionPlan.Skip.INCONSISTENT, decide(0, beyond).skip());

        List<RestoredSnapshot> outBeyond = List.of(snap(100, SEG, 90, 11 * SEG, 20261106));
        assertEquals(RetentionPlan.Skip.INCONSISTENT, decide(0, outBeyond).skip());

        RetentionPlan.Decision d = RetentionPlan.decide(0, in(0, 10 * SEG), out(0, 10 * SEG), fourCloses(),
                Long.MAX_VALUE, true, 11 * SEG, SEG);
        assertEquals(RetentionPlan.Skip.INCONSISTENT, d.skip());
    }

    @Test
    void aSnapshotOfAnotherInboundRecordingIsASkipOnDoubt() {
        RestoredSnapshot other = new RestoredSnapshot(100, SEG, 9L, 90, 1L, 0L, List.of(), 2, false, -1,
                20261106, new byte[8], OUT_ID, SEG, 20261106);
        assertEquals(RetentionPlan.Skip.INCONSISTENT, decide(0, List.of(other)).skip());
    }
}
