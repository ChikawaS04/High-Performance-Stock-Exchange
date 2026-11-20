package journal;

import io.aeron.archive.client.AeronArchive;
import recovery.RestoredSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Where journal retention may move each recording's start (P16-7, D14, R1 to R3). Pure: it takes
 * what the retention thread read and returns a decision; it reads nothing and purges nothing.
 *
 * <b>Window (R1).</b> Closed business days are counted by session closes: each valid snapshot a
 * SESSION_CLOSE requested carries its business date ({@link RestoredSnapshot#closeDate()}), and the
 * lowest-seq one per date is that date's close snapshot. With {@code days} = N, the window starts at
 * the close snapshot of the (N+1)th most recent close: its inbound position, and its outbound
 * position (a lower bound of the outbound boundary; an unknown one falls back to an older close
 * snapshot's). Until N+1 closes exist the window is the whole journal. Days without a session have
 * no close and do not count.
 *
 * <b>Floors.</b> Inbound: the lowest of the window, the oldest usable snapshot's position (usable is
 * what recovery would accept: valid, on this inbound recording, between the start and the recorded
 * position, and at or below the outbound seq known to be on file, rule 3), and the Reporter's
 * committed inbound checkpoint when a Reporter is required. Outbound: the lowest of the window and
 * the Reporter's committed outbound checkpoint; recovery needs only the outbound tail (the last seq
 * and event), so no snapshot floor applies to it.
 *
 * <b>New start (R2).</b> The floor is clamped to the recorded position minus one, so the segment
 * holding the last recorded byte (the tail recovery reads) always survives, then rounded down to its
 * segment base. A start that would not move means nothing to purge. Anything that does not add up
 * (a position beyond the recording, a snapshot of another recording) is a skip on doubt.
 */
public final class RetentionPlan {

    /** Why a run purges nothing on doubt (R7). */
    public enum Skip {
        VENUE_NOT_RUNNING, REPORTER_NOT_RUNNING, DESCRIPTOR_UNAVAILABLE, SNAPSHOTS_UNREADABLE, NO_USABLE_SNAPSHOT,
        OUTBOUND_NOT_SETTLED, INCONSISTENT, ARCHIVE_REFUSED_REPLAY, ARCHIVE_REFUSED, UNEXPECTED
    }

    /** One recording as the retention thread saw it. */
    public record Stream(long recordingId, long start, long recorded, int termLength, int segmentLength) {
    }

    /**
     * @param skip             null when the decision may be carried out
     * @param detail           what the skip is about, or a note on the window
     * @param inboundNewStart  the inbound recording's new start (its start when nothing moves)
     * @param outboundNewStart the outbound recording's new start (its start when nothing moves)
     * @param closesSeen       distinct close dates among the valid close snapshots
     */
    public record Decision(Skip skip, String detail, long inboundNewStart, long outboundNewStart, int closesSeen) {
        static Decision skip(Skip skip, String detail, int closesSeen, Stream in, Stream out) {
            return new Decision(skip, detail, in.start(), out.start(), closesSeen);
        }
    }

    private RetentionPlan() {
    }

    /**
     * @param days                 {@code JOURNAL_RETENTION_DAYS}
     * @param inbound              the inbound recording
     * @param outbound             the outbound recording
     * @param snapshots            every valid snapshot on disk, any order
     * @param settledOutboundSeq   an outbound seq known to be in the outbound recording (rule 3's bound)
     * @param reporterRequired     whether a Reporter floor applies ({@code DB_URL} set)
     * @param reporterInbound      its committed inbound position, or -1
     * @param reporterOutbound     its committed outbound position, or -1
     */
    public static Decision decide(int days, Stream inbound, Stream outbound, List<RestoredSnapshot> snapshots,
                                  long settledOutboundSeq, boolean reporterRequired, long reporterInbound,
                                  long reporterOutbound) {
        // Close snapshots: the lowest seq per close date.
        TreeMap<Integer, RestoredSnapshot> closeOf = new TreeMap<>(Comparator.reverseOrder());
        for (RestoredSnapshot s : snapshots) {
            if (!s.isCloseSnapshot()) continue;
            RestoredSnapshot seen = closeOf.get(s.closeDate());
            if (seen == null || s.inboundSeq() < seen.inboundSeq()) closeOf.put(s.closeDate(), s);
        }
        int closes = closeOf.size();

        if (reporterRequired && (reporterInbound < 0 || reporterOutbound < 0)) {
            return Decision.skip(Skip.REPORTER_NOT_RUNNING, "no committed checkpoint yet", closes, inbound, outbound);
        }
        if (reporterRequired && (reporterInbound > inbound.recorded() || reporterOutbound > outbound.recorded())) {
            return Decision.skip(Skip.INCONSISTENT, "the Reporter's checkpoint is beyond a recording's end", closes,
                    inbound, outbound);
        }

        // No window yet: nothing can move, so nothing is in doubt (a fresh venue has no snapshot at all).
        if (closes < days + 1) {
            return new Decision(null, closes + " of " + (days + 1) + " closes needed before the window starts",
                    inbound.start(), outbound.start(), closes);
        }

        // The oldest usable snapshot (inbound floor).
        long snapshotFloor = Long.MAX_VALUE;
        for (RestoredSnapshot s : snapshots) {
            if (s.inboundRecordingId() != inbound.recordingId()) {
                return Decision.skip(Skip.INCONSISTENT, "snapshot at seq " + s.inboundSeq() + " names inbound recording "
                        + s.inboundRecordingId() + ", not " + inbound.recordingId(), closes, inbound, outbound);
            }
            if (s.inboundPosition() > inbound.recorded()) {
                return Decision.skip(Skip.INCONSISTENT, "snapshot at seq " + s.inboundSeq() + " is beyond the inbound"
                        + " recording's end", closes, inbound, outbound);
            }
            if (s.inboundPosition() >= inbound.start() && s.outboundLastSeq() <= settledOutboundSeq) {
                snapshotFloor = Math.min(snapshotFloor, s.inboundPosition());
            }
        }
        if (snapshotFloor == Long.MAX_VALUE) {
            return Decision.skip(Skip.NO_USABLE_SNAPSHOT, "no valid snapshot at or after inbound position "
                    + inbound.start(), closes, inbound, outbound);
        }

        // The window: the close snapshot of the (N+1)th most recent close.
        List<RestoredSnapshot> ordered = new ArrayList<>(closeOf.values());   // newest close first
        RestoredSnapshot edge = ordered.get(days);
        long windowIn = edge.inboundPosition();
        long windowOut = -1L;
        for (int i = days; i < ordered.size() && windowOut < 0; i++) {
            RestoredSnapshot s = ordered.get(i);
            if (s.outboundRecordingId() == outbound.recordingId() && s.outboundPosition() >= 0) {
                windowOut = s.outboundPosition();
            }
        }
        if (windowOut < 0) windowOut = outbound.start();
        if (windowOut > outbound.recorded()) {
            return Decision.skip(Skip.INCONSISTENT, "close snapshot of " + edge.closeDate() + " is beyond the"
                    + " outbound recording's end", closes, inbound, outbound);
        }
        String note = "window starts at the close of " + edge.closeDate();

        long inFloor = Math.min(windowIn, snapshotFloor);
        long outFloor = windowOut;
        if (reporterRequired) {
            inFloor = Math.min(inFloor, reporterInbound);
            outFloor = Math.min(outFloor, reporterOutbound);
        }
        return new Decision(null, note, newStart(inbound, inFloor), newStart(outbound, outFloor), closes);
    }

    /**
     * The new start for {@code floor}: the segment base of the floor, never past the segment holding the
     * last recorded byte, never before the current start.
     */
    static long newStart(Stream stream, long floor) {
        if (stream.recorded() <= stream.start()) return stream.start();
        long clamped = Math.min(floor, stream.recorded() - 1);
        if (clamped <= stream.start()) return stream.start();
        long base = AeronArchive.segmentFileBasePosition(stream.start(), clamped, stream.termLength(),
                stream.segmentLength());
        return Math.max(base, stream.start());
    }
}
