package participant;

/**
 * Attribution scheme shared by the strategies and the {@link PriceTracker}.
 *
 * <p>The gateway uses the FIX tag-11 ClOrdID as the engine orderId, and every EXEC fill frame
 * carries both {@code aggressorOrderId} and {@code passiveOrderId}. So if each bot draws its
 * clOrdIds from a disjoint range, any order id on the wire identifies the bot that sent it with
 * no change to the engine, gateway or frontend. Ranges are wide and non-overlapping; a bot's
 * {@link IdSequence} walks upward inside its range and never repeats (a repeated id would
 * overwrite the engine's O(1) cancel-lookup entry for a still-resting order).
 */
public final class Bots {

    private Bots() { }

    public static final long MAKER_BASE = 1_000_000L;
    public static final long TAKER_BASE = 2_000_000L;
    /** Exclusive upper bound of the taker range; also the first id no bot uses. */
    public static final long RANGE_SPAN = 1_000_000L;

    public enum Owner { MAKER, TAKER, OTHER }

    /** Classify a clOrdId (engine orderId) by which bot's range it falls in. */
    public static Owner owner(long clOrdId) {
        if (clOrdId >= MAKER_BASE && clOrdId < MAKER_BASE + RANGE_SPAN) return Owner.MAKER;
        if (clOrdId >= TAKER_BASE && clOrdId < TAKER_BASE + RANGE_SPAN) return Owner.TAKER;
        return Owner.OTHER;
    }

    /**
     * Monotonic clOrdId generator confined to one bot's range. Not thread-safe by design: a
     * strategy and its client touch it only on the one serialized executor thread.
     */
    public static final class IdSequence {
        private final long base;
        private long next;

        public IdSequence(long base) {
            this.base = base;
            this.next = base;
        }

        /** The next unused clOrdId in this range. */
        public long next() {
            long id = next++;
            if (next >= base + RANGE_SPAN) {
                throw new IllegalStateException("clOrdId range exhausted for base " + base);
            }
            return id;
        }
    }
}
