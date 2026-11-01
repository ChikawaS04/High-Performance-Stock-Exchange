package engine;

import model.Order;

/**
 * Receives every resting order of a {@link MatchingEngine} in restore order (P15-5): bids
 * best-first, level by level, each level in queue order; then asks the same way; then the bid
 * peg pool and the ask peg pool, each in FIFO order. Feeding the orders back to
 * {@link MatchingEngine#restoreResting} in this order rebuilds identical queues and pools.
 *
 * Called on the engine thread (or after it has stopped). The visitor must not keep or mutate
 * the orders.
 */
@FunctionalInterface
public interface RestingOrderVisitor {

    /** @param pegged true for an order in a peg pool, false for one on a lit price level */
    void onRestingOrder(Order order, boolean pegged);
}
