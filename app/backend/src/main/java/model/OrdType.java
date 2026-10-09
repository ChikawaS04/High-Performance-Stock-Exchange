package model;

/**
 * Order type (FIX tag 40). Carried on every order from Phase 14.
 *
 * <ul>
 *   <li>LIMIT   — a priced order that rests in the lit book on the one-cent tick.</li>
 *   <li>PEG_MID — a non-displayed midpoint peg: it carries no price of its own
 *       ({@link Prices#NA}) and executes only at the midpoint of the venue's own
 *       lit book, resting in a separate FIFO peg pool rather than the price map.</li>
 * </ul>
 *
 * The FIX wire codes (2 = Limit, P = Pegged) and the ExecInst tag 18=M that
 * qualifies a peg live in the gateway and the JSON-to-FIX encoder, not here, so
 * this enum stays framework-free like {@link Side} and {@link TimeInForce}.
 */
public enum OrdType {
    LIMIT, PEG_MID
}
