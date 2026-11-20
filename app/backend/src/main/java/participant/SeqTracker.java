package participant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies the venue's sequenced frames (EXEC and SESSION, seq gap-free from 1) exactly once and in
 * seq order (P16-5, D16). Live frames and RESEND frames reach a client interleaved and possibly
 * twice (SRS 3.6), so:
 *
 * <ul>
 *   <li>{@code seq < next}: already applied, dropped;</li>
 *   <li>{@code seq == next}: applied, then every buffered frame that is now contiguous;</li>
 *   <li>{@code seq > next}: buffered until the gap below it is filled.</li>
 * </ul>
 *
 * Without a baseline (a fresh client, or after the venue lost its history) the first frame offered
 * sets it. Single-thread confined.
 *
 * @param <T> the decoded frame
 */
final class SeqTracker<T> {

    private long next = -1;
    private final TreeMap<Long, T> pending = new TreeMap<>();

    boolean hasBaseline() {
        return next > 0;
    }

    /** The seq expected next; -1 without a baseline. */
    long next() {
        return next;
    }

    /** The last seq applied; -1 without a baseline. */
    long lastApplied() {
        return next > 0 ? next - 1 : -1;
    }

    /** Forget everything: the next frame offered sets a new baseline. */
    void reset() {
        next = -1;
        pending.clear();
    }

    /**
     * Offer one frame.
     *
     * @return the frames to apply now, in seq order (empty for a duplicate or a frame past a gap)
     */
    List<T> offer(long seq, T frame) {
        if (seq < 1) {
            return List.of();
        }
        if (next < 0) {
            next = seq;
        }
        if (seq < next) {
            return List.of();
        }
        if (seq > next) {
            pending.putIfAbsent(seq, frame);
            return List.of();
        }
        List<T> out = new ArrayList<>();
        out.add(frame);
        next++;
        // Anything buffered at or below the new next is either the next frame or a duplicate.
        while (!pending.isEmpty()) {
            Map.Entry<Long, T> first = pending.firstEntry();
            if (first.getKey() < next) {
                pending.pollFirstEntry();
            } else if (first.getKey() == next) {
                out.add(pending.pollFirstEntry().getValue());
                next++;
            } else {
                break;
            }
        }
        return out;
    }

    /** True while frames are buffered behind a gap. */
    boolean hasGap() {
        return !pending.isEmpty();
    }

    int buffered() {
        return pending.size();
    }
}
