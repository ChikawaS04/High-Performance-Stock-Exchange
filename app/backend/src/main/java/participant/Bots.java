package participant;

/**
 * Attribution scheme shared by the strategies, the {@link PriceTracker} and the terminal.
 *
 * <p>The gateway uses the FIX tag-11 ClOrdID as the engine orderId, and every EXEC fill frame
 * carries both {@code aggressorOrderId} and {@code passiveOrderId}. So if each bot draws its
 * clOrdIds from a disjoint block, any order id on the wire identifies the bot that sent it with
 * no change to the engine or gateway. The terminal mirrors this table in
 * {@code frontend/src/participants.ts}; the two must stay in step.
 *
 * <p>Bot k owns {@code [k * BLOCK, (k + 1) * BLOCK)} with {@code BLOCK = 1e14}. The largest id any
 * bot can use is below 6e14, far under 2^53, so every id is exact as a JSON number and in
 * JavaScript. The terminal seeds its own ids from {@code Date.now()} (about 1.8e12), below the
 * first block, so a terminal id can never fall in a bot block.
 *
 * <p><b>Restart safety.</b> A run starts each bot at {@code base + (nowMillis - ID_EPOCH_MILLIS)},
 * the same idea as the terminal's {@code Date.now()} seed: a GTC order left resting by an earlier
 * run keeps its id, and a later run starts past it unless the earlier run sent more than one id
 * per millisecond of the gap. The bots send a few ids a second. Reusing a resting id would make
 * the engine reject the new order as a duplicate.
 */
public final class Bots {

    private Bots() { }

    /** Width of one bot's id block. */
    public static final long BLOCK = 100_000_000_000_000L;

    /** 2026-01-01T00:00:00Z in epoch millis; the origin of the per-run start offset. */
    public static final long ID_EPOCH_MILLIS = 1_767_225_600_000L;

    /** Who sent an order, by id block. {@link #OTHER} is anything outside every bot block. */
    public enum Owner {
        MAKER(1), TREND(2), REVERT(3), NOISE(4), INST(5), OTHER(0);

        private final int block;

        Owner(int block) {
            this.block = block;
        }

        /** Inclusive lower bound of this owner's block; 0 for {@link #OTHER}. */
        public long base() {
            return block * BLOCK;
        }

        /** True for the five bots. */
        public boolean isBot() {
            return block > 0;
        }
    }

    /** Classify a clOrdId (engine orderId) by which bot block it falls in. */
    public static Owner owner(long clOrdId) {
        if (clOrdId < BLOCK) {
            return Owner.OTHER;
        }
        long block = clOrdId / BLOCK;
        for (Owner o : Owner.values()) {
            if (o.block == block && o.isBot()) {
                return o;
            }
        }
        return Owner.OTHER;
    }

    /** First id a run started at {@code nowMillis} hands out for {@code owner}. */
    public static long runStart(Owner owner, long nowMillis) {
        if (!owner.isBot()) {
            throw new IllegalArgumentException("only a bot owns an id block: " + owner);
        }
        return owner.base() + Math.max(0L, nowMillis - ID_EPOCH_MILLIS);
    }

    /**
     * Monotonic clOrdId generator confined to one bot's block. Not thread-safe by design: a
     * strategy and its client touch it only on the one serialized executor thread.
     */
    public static final class IdSequence {
        private final Owner owner;
        private long next;

        /** Starts at the first id of the block. Tests use this for predictable ids. */
        public IdSequence(Owner owner) {
            this(owner, owner.base());
        }

        public IdSequence(Owner owner, long start) {
            if (!owner.isBot()) {
                throw new IllegalArgumentException("only a bot owns an id block: " + owner);
            }
            if (start < owner.base() || start >= owner.base() + BLOCK) {
                throw new IllegalArgumentException("start " + start + " is outside the " + owner + " block");
            }
            this.owner = owner;
            this.next = start;
        }

        public Owner owner() {
            return owner;
        }

        /** The next unused clOrdId in this block. */
        public long next() {
            if (next >= owner.base() + BLOCK) {
                throw new IllegalStateException("clOrdId block exhausted for " + owner);
            }
            return next++;
        }
    }
}
