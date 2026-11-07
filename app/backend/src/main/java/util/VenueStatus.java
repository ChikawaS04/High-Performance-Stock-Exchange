package util;

/**
 * What {@code GET /health} reports (SRS 3.6, P15-7): whether the venue is running, whether a
 * session is open (P15-8), and how far each stage of the inbound and outbound paths has got, so
 * lag between them is visible.
 *
 * Every method is readable from any thread without a lock: each reads a value its owning thread
 * publishes with a release store (or a volatile), so a reader sees a recent value, never a torn
 * one. The values are read one at a time, not as a consistent cut, and each is monotonic.
 *
 * Ordering between the values, for a reader:
 * <ul>
 *   <li>Outbound: {@link #recordedSeq()} never exceeds {@link #publishedSeq()}, because the
 *       recorder only sees slots already published. Reading recorded first and published second
 *       therefore never shows recorded above published.</li>
 *   <li>Inbound: {@link #appliedSeq()} can briefly exceed {@link #journaledSeq()}. The sequencer
 *       stores its journaled seq after the offer returns, and in between the Archive can record
 *       the command and the engine can apply it. The gap closes as soon as the sequencer's store
 *       lands; a reader must not treat applied above journaled as an error.</li>
 * </ul>
 *
 * "Journaled" and "recorded" mean offered to the recorded publication; the Archive's own
 * recorded position can trail that by a moment. The engine never applies a command before the
 * Archive has recorded it (SRS 5.7), whatever these counters show.
 */
public interface VenueStatus {

    /** True once recovery has completed and no stage has reported a fail-stop. */
    boolean running();

    /** The last inbound seq the engine applied. */
    long appliedSeq();

    /** The last inbound seq the sequencer offered to the journal. */
    long journaledSeq();

    /** The highest outbound seq published on the outbound ring. */
    long publishedSeq();

    /** The last outbound seq offered to the outbound recording. */
    long recordedSeq();

    /**
     * The session state the engine thread last published (P15-8), as a {@link #sessionWord}: one
     * value, so the state and its business date can never be read from two different moments.
     */
    long session();

    /** The Reporter's status (P15-10), or null when none runs, because {@code DB_URL} is unset. */
    default ReporterStatus reporter() {
        return null;
    }

    /** End-of-day clearing's status (P15-11), or null when none runs, because {@code DB_URL} is unset. */
    default ClearingStatus clearing() {
        return null;
    }

    /**
     * Packs a session state into one long: {@code 0} when no session was ever opened or closed,
     * {@code +businessDate} while open on that day, {@code -lastClosedBusinessDate} while closed.
     * Business dates are positive YYYYMMDD integers.
     */
    static long sessionWord(boolean open, int businessDate, int lastClosedBusinessDate) {
        if (open) return businessDate;
        return lastClosedBusinessDate > 0 ? -(long) lastClosedBusinessDate : 0L;
    }

    /** Whether {@code word} says a session is open. */
    static boolean isSessionOpen(long word) {
        return word > 0;
    }

    /** The open day when open, the last closed day when closed, or -1 if there is none. */
    static int sessionDate(long word) {
        return word == 0 ? -1 : (int) Math.abs(word);
    }
}
