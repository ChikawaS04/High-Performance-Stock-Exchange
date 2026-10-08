package model;

/** Price representation (SRS §4, Phase 14). All prices are long units of $0.0001. */
public final class Prices {
    private Prices() { }

    /** Units per dollar: $150.25 = 1_502_500L. */
    public static final long SCALE = 10_000L;

    /** Minimum limit-price increment: one cent = 100 units. */
    public static final long TICK = 100L;

    /** Absent-price sentinel (empty book side, NA wire field, peg limit). */
    public static final long NA = -1L;

    public static boolean isOnTick(long price) { return price > 0 && price % TICK == 0; }
}
