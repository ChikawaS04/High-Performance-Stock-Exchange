package model;

/**
 * Time in force (FIX tag 59). Carried on every order from Phase 14.
 *
 * <ul>
 *   <li>GTC — Good Til Cancelled: a remainder rests on the book.</li>
 *   <li>IOC — Immediate Or Cancel: a remainder expires rather than resting.</li>
 *   <li>FOK — Fill Or Kill: fills completely or expires, with no change to the book.</li>
 * </ul>
 *
 * The FIX wire codes (1/3/4) live in the gateway and the JSON-to-FIX encoder,
 * not here, so this enum stays framework-free like {@link Side}. Engine
 * semantics for IOC and FOK land in Phase 14-5; P14-4 only carries the value
 * from the wire to the engine, with GTC behaviour throughout.
 */
public enum TimeInForce {
    GTC, IOC, FOK
}
