package engine;

import event.BookSnapshotEvent;
import model.Order;
import model.OrdType;
import model.Prices;
import model.Side;
import model.TimeInForce;
import model.Trade;
import util.IDGenerator;

import java.util.*;

public class MatchingEngine implements BookView {

    private final TreeMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());

    private final TreeMap<Long, Deque<Order>> asks = new TreeMap<>();

    private final List<Trade> trades = new ArrayList<>();

    private final Map<Long, Order> openOrders = new HashMap<>();

    /** Resting midpoint peg orders in FIFO order, outside the price maps (Phase 14 D9); one pool per side. */
    private final Deque<Order> pegBids = new ArrayDeque<>();
    private final Deque<Order> pegAsks = new ArrayDeque<>();

    private ExecutionListener executionListener = ExecutionListener.NO_OP;

    /** Wire the outbound adapter in Step 7.5; defaults to NO_OP for the console/demo path. */
    public void setExecutionListener(ExecutionListener listener) {
        this.executionListener = (listener == null) ? ExecutionListener.NO_OP : listener;
    }

    /**
     * Price-time-priority eligibility: can an incoming order of {@code aggressorSide}
     * with limit {@code limit} trade against resting liquidity priced at {@code contraPrice}?
     * A buy is marketable against an ask at or below its limit; a sell against a bid at or
     * above it. Extracted (Phase 14 D6) from the former inline break tests so the match
     * loops and the FOK availability check share ONE rule and cannot disagree — the single
     * outcome FOK forbids is a partial fill, which a divergence here would cause.
     */
    private static boolean marketable(Side aggressorSide, long limit, long contraPrice) {
        return (aggressorSide == Side.BUY) ? contraPrice <= limit : contraPrice >= limit;
    }

    /**
     * The venue midpoint, computed from the LIT book only (Phase 14 D9): the exact mean of the
     * best bid and best ask, or {@link Prices#NA} when either lit side is empty. Pegs never
     * contribute to the best bid or ask, so this carries no circular definition, and because both
     * lit tops are on the one-cent tick the mean is always an exact multiple of 50 units and is
     * never rounded (SRS 4).
     */
    private long mid() {
        long bestBid = getBestBid();
        long bestAsk = getBestAsk();
        return (bestBid == Prices.NA || bestAsk == Prices.NA) ? Prices.NA : (bestBid + bestAsk) / 2;
    }

    /**
     * Mid-crossing eligibility (Phase 14 D10): does an order of {@code side} with limit
     * {@code limit} reach the midpoint {@code mid}? A buy crosses at or above the mid, a sell at or
     * below it. Shared by {@link #addOrder} and {@link #canFillCompletely}, exactly as
     * {@link #marketable} is shared on the lit path, so the peg-pool availability check and the
     * peg-pool match step can never disagree. Callers pass a real mid (not {@link Prices#NA}).
     */
    private static boolean crossesMid(Side side, long limit, long mid) {
        return (side == Side.BUY) ? limit >= mid : limit <= mid;
    }

    private void matchBuy(Order buyOrder) {
        while (buyOrder.getQuantity() > 0 && !asks.isEmpty()) {
            Map.Entry<Long, Deque<Order>> bestAskEntry  = asks.firstEntry();
            long askPrice = bestAskEntry.getKey();

            if (!marketable(Side.BUY, buyOrder.getPrice(), askPrice)) break;

            Deque<Order> bestAskQueue = bestAskEntry.getValue();
            Order askOrder = bestAskQueue.getFirst();

            int quantityMatched = Math.min(buyOrder.getQuantity(), askOrder.getVisibleQty());
            buyOrder.fill(quantityMatched);
            askOrder.fill(quantityMatched);

            long tradeID = IDGenerator.nextTradeID();
            trades.add(new Trade(
                    tradeID,
                    buyOrder.getOrderID(),
                    askOrder.getOrderID(),
                    buyOrder.getParticipantID(),
                    askOrder.getParticipantID(),
                    askPrice,
                    quantityMatched,
                    System.nanoTime()
            ));
            executionListener.onFill(
                    buyOrder.getOrderID(),   // aggressor
                    askOrder.getOrderID(),   // passive
                    tradeID,
                    askPrice,
                    quantityMatched,
                    buyOrder.getQuantity()   // aggressor remaining after this fill
            );

            if (askOrder.getQuantity() == 0) {
                bestAskQueue.removeFirst();
                openOrders.remove(askOrder.getOrderID());   // passive fully consumed — no longer cancellable
                if (bestAskQueue.isEmpty()) {
                    asks.pollFirstEntry();
                }
            } else if (askOrder.getVisibleQty() == 0) {
                // iceberg tip exhausted with reserve remaining: reload a fresh slice at the back of
                // the level's queue, losing time priority (Phase 14 D7, SRS 3.3).
                bestAskQueue.pollFirst();
                askOrder.refreshDisplay();
                bestAskQueue.addLast(askOrder);
            }
        }
    }

    private void matchSell(Order sellOrder) {
        while (sellOrder.getQuantity() > 0 && !bids.isEmpty()) {
            Map.Entry<Long, Deque<Order>> bestBidEntry = bids.firstEntry();
            long bidPrice = bestBidEntry.getKey();

            if (!marketable(Side.SELL, sellOrder.getPrice(), bidPrice)) break;

            Deque<Order> bestBidQueue = bestBidEntry.getValue();
            Order bidOrder = bestBidQueue.getFirst();

            int quantityMatched = Math.min(sellOrder.getQuantity(), bidOrder.getVisibleQty());
            sellOrder.fill(quantityMatched);
            bidOrder.fill(quantityMatched);

            long tradeID = IDGenerator.nextTradeID();
            trades.add(new Trade(
                    tradeID,
                    bidOrder.getOrderID(),
                    sellOrder.getOrderID(),
                    bidOrder.getParticipantID(),
                    sellOrder.getParticipantID(),
                    bidPrice,
                    quantityMatched,
                    System.nanoTime()
            ));
            executionListener.onFill(
                    sellOrder.getOrderID(),  // aggressor
                    bidOrder.getOrderID(),   // passive
                    tradeID,
                    bidPrice,
                    quantityMatched,
                    sellOrder.getQuantity()  // aggressor remaining after this fill
            );

            if (bidOrder.getQuantity() == 0) {
                bestBidQueue.removeFirst();
                openOrders.remove(bidOrder.getOrderID());   // passive fully consumed — no longer cancellable
                if (bestBidQueue.isEmpty()) {
                    bids.pollFirstEntry();
                }
            } else if (bidOrder.getVisibleQty() == 0) {
                // iceberg tip exhausted with reserve remaining: reload a fresh slice at the back of
                // the level's queue, losing time priority (Phase 14 D7, SRS 3.3).
                bestBidQueue.pollFirst();
                bidOrder.refreshDisplay();
                bestBidQueue.addLast(bidOrder);
            }
        }
    }

    /**
     * FOK pre-trade availability check (SRS §3.3, Phase 14 D6). Walks the opposite side
     * best-first, summing the total resting quantity at every level eligible under the same
     * {@link #marketable} predicate the match loops use, and reports whether that covers the
     * order. Reads only — it never calls fill() or touches a deque — so a failing probe
     * leaves every level, quantity and queue position byte-for-byte unchanged.
     *
     * <p>The eligible liquidity depends on the order type (D6/D10). Iceberg reserve needs no
     * special handling here: getQuantity() is already an order's TOTAL remaining size, so a
     * resting iceberg's hidden reserve is counted. A midpoint peg draws on the opposite peg
     * pool at the mid; a lit order that crosses the mid counts that pool before the lit levels.
     *
     * <p>Allocation: the enhanced-for loops allocate map/deque iterators that do not escape,
     * the same already-documented book-structure allocation as snapshotInto; measured, not
     * assumed away, at the Phase 14 benchmark re-measure.
     */
    private boolean canFillCompletely(Order order) {
        long needed = order.getQuantity();
        long available = 0;
        long mid = mid();

        // A midpoint peg draws only on the opposite peg pool, and only when a mid exists; with no
        // reference it cannot fill, so a FOK peg with no mid expires (D10 rule 6).
        if (order.getOrdType() == OrdType.PEG_MID) {
            if (mid == Prices.NA) return false;
            return pegPoolTotal(order.getSide()) >= needed;
        }

        // A lit order that crosses the mid takes the opposite peg pool first (D10 rule 1), so that
        // liquidity counts toward a lit FOK's availability before the lit levels do.
        if (mid != Prices.NA && crossesMid(order.getSide(), order.getPrice(), mid)) {
            available += pegPoolTotal(order.getSide());
            if (available >= needed) return true;
        }

        TreeMap<Long, Deque<Order>> contra = (order.getSide() == Side.BUY) ? asks : bids;
        for (Map.Entry<Long, Deque<Order>> level : contra.entrySet()) {
            if (!marketable(order.getSide(), order.getPrice(), level.getKey())) break;
            for (Order resting : level.getValue()) {
                available += resting.getQuantity();
                if (available >= needed) return true;
            }
        }
        return available >= needed;
    }

    /** Total resting quantity in the peg pool OPPOSITE the given aggressor side (Phase 14 D6/D10). */
    private long pegPoolTotal(Side aggressorSide) {
        Deque<Order> pool = (aggressorSide == Side.BUY) ? pegAsks : pegBids;
        long total = 0;
        for (Order o : pool) {
            total += o.getQuantity();
        }
        return total;
    }

    /**
     * Admits one order: run the midpoint-peg step and the lit match, then dispose of any unfilled
     * remainder by its time in force (Phase 14 D4-D6, SRS §3.3).
     *
     * <ul>
     *   <li><b>FOK</b> runs a pre-trade probe BEFORE any book change. If the eligible
     *       resting liquidity cannot cover the whole order, it expires in full and the book
     *       is left untouched. A passing probe guarantees the subsequent match fills it
     *       completely, so the remainder below is zero.</li>
     *   <li><b>GTC</b> rests any remainder and is acknowledged with onAccepted (unchanged
     *       pre-Phase-14 behaviour).</li>
     *   <li><b>IOC</b> never rests: any remainder expires. A fully filled IOC emits only its
     *       fills; one that finds nothing emits only the expiry.</li>
     * </ul>
     *
     * An IOC or FOK order is therefore never registered in openOrders and never acknowledged
     * with onAccepted. The FOK branch of the remainder handling is reached only if the shared
     * {@link #marketable} invariant were ever violated; expiring there is the safe outcome,
     * since FOK must never partially rest.
     */
    public void addOrder(Order order) {
        TimeInForce tif = order.getTimeInForce();

        // FOK pre-trade probe, now spanning the peg pool as well as the lit book (D6/D10). On
        // failure the whole order expires and neither the book nor the pools are touched.
        if (tif == TimeInForce.FOK && !canFillCompletely(order)) {
            executionListener.onExpired(order.getOrderID(), order.getQuantity());
            return;
        }

        // Computed once; constant for this order's peg fills, since peg fills never move the lit book.
        long mid = mid();

        if (order.getOrdType() == OrdType.PEG_MID) {
            // Incoming peg: fills ONLY against the opposite peg pool at the mid (D10 rule 2), never
            // lit. With no mid it cannot trade (D10 rule 3).
            if (mid != Prices.NA) {
                matchPegPool(order, mid);
            }
            if (order.getQuantity() > 0) {
                if (tif == TimeInForce.GTC) {
                    restPeg(order);   // rests in its pool (inactive while no mid); ACCEPTED at price NA
                } else {
                    order.expire();
                    executionListener.onExpired(order.getOrderID(), order.getQuantity());
                }
            }
            return;   // a resting peg cannot create a mid, so there is no pool cross to run here
        }

        // Lit order (limit or iceberg): if it crosses the mid, take the opposite peg pool at the
        // mid FIRST (the mid betters any lit level on that side, D10 rule 1), then walk the lit
        // book exactly as before.
        if (mid != Prices.NA && crossesMid(order.getSide(), order.getPrice(), mid)) {
            matchPegPool(order, mid);
        }

        if (order.getSide() == Side.BUY) {
            matchBuy(order);
        } else {
            matchSell(order);
        }

        if (order.getQuantity() > 0) {
            if (tif == TimeInForce.GTC) {
                addToBook((order.getSide() == Side.BUY) ? bids : asks, order);
                executionListener.onAccepted(order.getOrderID(), order.getPrice(), order.getQuantity());
            } else {
                order.expire();
                executionListener.onExpired(order.getOrderID(), order.getQuantity());
            }
        }

        // A lit order may have just rested onto a previously empty side, creating a mid. Cross the
        // peg pools head-to-head at that mid (D10 rule 4); otherwise this is two isEmpty() checks.
        crossPegPools();
    }

    /**
     * Fills an incoming order against the OPPOSITE peg pool at the fixed midpoint {@code mid}, in
     * FIFO order (Phase 14 D10 rules 1-2). The incoming order is the aggressor and each resting peg
     * is passive; every peg trade is recorded as a {@link Trade} at the mid like any other fill. A
     * fully consumed resting peg leaves its pool and {@code openOrders}. {@code mid} is passed in
     * because the caller fixed it before any book change, so it is constant across all of these
     * fills even once a lit aggressor continues into the lit book.
     */
    private void matchPegPool(Order aggressor, long mid) {
        Deque<Order> pool = (aggressor.getSide() == Side.BUY) ? pegAsks : pegBids;

        while (aggressor.getQuantity() > 0 && !pool.isEmpty()) {
            Order resting = pool.getFirst();
            int quantityMatched = Math.min(aggressor.getQuantity(), resting.getQuantity());
            aggressor.fill(quantityMatched);
            resting.fill(quantityMatched);

            boolean aggressorBuys = (aggressor.getSide() == Side.BUY);
            long buyId   = aggressorBuys ? aggressor.getOrderID()       : resting.getOrderID();
            long sellId  = aggressorBuys ? resting.getOrderID()         : aggressor.getOrderID();
            long buyPid  = aggressorBuys ? aggressor.getParticipantID() : resting.getParticipantID();
            long sellPid = aggressorBuys ? resting.getParticipantID()   : aggressor.getParticipantID();

            long tradeID = IDGenerator.nextTradeID();
            trades.add(new Trade(tradeID, buyId, sellId, buyPid, sellPid, mid, quantityMatched, System.nanoTime()));
            executionListener.onFill(
                    aggressor.getOrderID(),   // aggressor
                    resting.getOrderID(),     // passive
                    tradeID,
                    mid,
                    quantityMatched,
                    aggressor.getQuantity()   // aggressor remaining after this fill
            );

            if (resting.getQuantity() == 0) {
                pool.removeFirst();
                openOrders.remove(resting.getOrderID());   // passive fully consumed — no longer cancellable
            }
        }
    }

    /**
     * Rests an incoming midpoint peg in its pool and acknowledges it (Phase 14 D9/D10). A peg is
     * registered in {@code openOrders} for O(1) cancel lookup like a lit order, but lives in the
     * FIFO peg pool rather than the price map, so it never appears in the lit book or a depth
     * snapshot. ORDER_ACCEPTED is published whether or not a mid currently exists, carrying the
     * peg's price, which is {@link Prices#NA}.
     */
    private void restPeg(Order order) {
        Deque<Order> pool = (order.getSide() == Side.BUY) ? pegBids : pegAsks;
        pool.addLast(order);
        openOrders.put(order.getOrderID(), order);
        executionListener.onAccepted(order.getOrderID(), order.getPrice(), order.getQuantity());
    }

    /**
     * Post-event peg-pool cross (Phase 14 D10 rule 4). When a mid exists and both pools hold
     * orders, the heads cross at the mid until one pool empties. Reachable only when pegs built up
     * on both sides while the lit book was one-sided and a lit order then rested onto the empty
     * side, creating a mid; a cancel can never create a mid, so it never needs this. The mid is
     * read once and is constant through the loop, because crossing pegs does not touch the lit
     * book. Neither crossing order is the incoming one, so the aggressor named in each fill is the
     * later-arriving peg (greater timeStamp, ties broken by greater orderID; decision F): the order
     * that waited longer provided the liquidity.
     */
    private void crossPegPools() {
        long mid = mid();
        if (mid == Prices.NA) return;

        while (!pegBids.isEmpty() && !pegAsks.isEmpty()) {
            Order bid = pegBids.getFirst();
            Order ask = pegAsks.getFirst();

            Order aggressor = laterArrival(bid, ask);
            Order passive = (aggressor == bid) ? ask : bid;

            int quantityMatched = Math.min(bid.getQuantity(), ask.getQuantity());
            bid.fill(quantityMatched);
            ask.fill(quantityMatched);

            long tradeID = IDGenerator.nextTradeID();
            trades.add(new Trade(
                    tradeID,
                    bid.getOrderID(),
                    ask.getOrderID(),
                    bid.getParticipantID(),
                    ask.getParticipantID(),
                    mid,
                    quantityMatched,
                    System.nanoTime()
            ));
            executionListener.onFill(
                    aggressor.getOrderID(),
                    passive.getOrderID(),
                    tradeID,
                    mid,
                    quantityMatched,
                    aggressor.getQuantity()
            );

            if (bid.getQuantity() == 0) {
                pegBids.removeFirst();
                openOrders.remove(bid.getOrderID());
            }
            if (ask.getQuantity() == 0) {
                pegAsks.removeFirst();
                openOrders.remove(ask.getOrderID());
            }
        }
    }

    /** The later-arriving of two resting pegs: greater timeStamp, ties broken by greater orderID (decision F). */
    private static Order laterArrival(Order a, Order b) {
        if (a.getTimeStamp() != b.getTimeStamp()) {
            return (a.getTimeStamp() > b.getTimeStamp()) ? a : b;
        }
        return (a.getOrderID() > b.getOrderID()) ? a : b;
    }

    private void addToBook(TreeMap<Long, Deque<Order>> book, Order order) {
        order.refreshDisplay();   // set the visible slice for the resting order (iceberg tip); no-op for a plain order
        book.computeIfAbsent(order.getPrice(), k -> new ArrayDeque<>()).addLast(order);
        openOrders.put(order.getOrderID(), order);   // register resting order for O(1) cancel
    }

    /**
     * @return true if a live resting order was found and cancelled; false for an
     *         unknown id or an order already fully filled. The outbound adapter
     *         maps this to ORDER_CANCELLED vs ORDER_REJECTED.
     */
    public boolean cancelOrder(long orderID) {
        Order order = openOrders.remove(orderID);
        if (order == null || order.getQuantity() == 0) return false;

        order.cancel();

        if (order.getOrdType() == OrdType.PEG_MID) {
            // A peg lives in its FIFO pool, not the price map (its price is Prices.NA). Remove by
            // identity, O(n), the same caveat as a lit cancel's deque removal.
            Deque<Order> pool = (order.getSide() == Side.BUY) ? pegBids : pegAsks;
            pool.remove(order);
        } else {
            TreeMap<Long, Deque<Order>> book = (order.getSide() == Side.BUY) ? bids : asks;
            Deque<Order> queue = book.get(order.getPrice());
            if (queue != null) {
                queue.remove(order);
                if (queue.isEmpty()) book.remove(order.getPrice());
            }
        }
        return true;
    }

    public List<Trade> getTrades() {
        return trades;
    }

    public void printBook() {
        System.out.println("=============== ORDER BOOK ===============");

        // Asks: highest at top, lowest nearest to spread
        for (Map.Entry<Long, Deque<Order>> entry : asks.descendingMap().entrySet()) {
            int totalQty = entry.getValue().stream().mapToInt(Order::getVisibleQty).sum();
            System.out.printf("  ASK  %10s   %6d%n", formatPrice(entry.getKey()), totalQty);
        }

        long bestBid = getBestBid();
        long bestAsk = getBestAsk();
        System.out.println("  ---------------------------------------");
        if (bestBid != -1L && bestAsk != -1L) {
            long spread = bestAsk - bestBid;
            long midpoint = (bestBid + bestAsk) / 2;
            System.out.printf("  Spread: %s     Mid: %s%n", formatPrice(spread), formatPrice(midpoint));
        } else {
            System.out.println("  No spread (one side empty)");
        }
        System.out.println("  ---------------------------------------");

        // Bids: highest first (natural iteration of reverse-ordered TreeMap)
        for (Map.Entry<Long, Deque<Order>> entry : bids.entrySet()) {
            int totalQty = entry.getValue().stream().mapToInt(Order::getVisibleQty).sum();
            System.out.printf("  BID  %10s   %6d%n", formatPrice(entry.getKey()), totalQty);
        }

        System.out.println("==========================================");
    }

    private String formatPrice(long priceUnits) {
        long dollars = priceUnits / Prices.SCALE;
        long frac = priceUnits % Prices.SCALE;       // 0..9999 ten-thousandths of a dollar
        String f = String.format("%04d", frac);
        int len = 4;
        while (len > 2 && f.charAt(len - 1) == '0') { len--; }   // trim to no fewer than two places
        return "$" + dollars + "." + f.substring(0, len);
    }

    @Override
    public long getBestBid() {
        return bids.isEmpty() ? -1L : bids.firstKey();
    }

    @Override
    public long getBestAsk() {
        return asks.isEmpty() ? -1L : asks.firstKey();
    }

    /**
     * Writes a bounded top-N depth snapshot into a caller-owned, reusable carrier.
     * Runs on the engine (consumer) thread only — the single thread that may read
     * {@code bids}/{@code asks} without synchronization (SRS §5.2). P4-2's handler calls
     * this at the end of each onEvent and publishes the slot on the snapshot ring.
     *
     * <p>Both sides are walked best-first (bids reverse-ordered, asks natural, so each map's
     * iteration order is already best→worst), aggregating visible resting quantity per price
     * level, truncated at {@code min(maxLevels, MAX_DEPTH_LEVELS)}. {@code bestBid}/
     * {@code bestAsk} carry the {@code -1L} empty-side sentinel. Fills all scalar fields and
     * the valid array prefix every call; array tails beyond the level counts are left as-is
     * (see the BookSnapshotEvent slot-reuse contract).
     *
     * <p><b>Allocation note.</b> The enhanced-for loops allocate map/deque iterators that do
     * not escape this method, so C2 escape analysis is expected to scalar-replace them after
     * warmup. To be validated under JMH in Phase 6; if they surface in allocation profiling,
     * switch to cached iterators or a per-level aggregate maintained on add/fill/cancel.
     */
    public void snapshotInto(BookSnapshotEvent target, int maxLevels) {
        int levels = Math.min(maxLevels, BookSnapshotEvent.MAX_DEPTH_LEVELS);

        target.bidLevelCount = fillSide(bids, target.bidPrices, target.bidQtys, levels);
        target.askLevelCount = fillSide(asks, target.askPrices, target.askQtys, levels);

        target.bestBid = getBestBid();
        target.bestAsk = getBestAsk();
        target.timestamp = System.nanoTime();
    }

    /**
     * Fills {@code prices}/{@code qtys} with up to {@code maxLevels} best-first levels from
     * {@code book}, aggregating visible resting quantity per price level (an iceberg contributes only its tip).
     *
     * @return the number of levels written (the authoritative count for this side)
     */
    private static int fillSide(TreeMap<Long, Deque<Order>> book,
                                long[] prices, long[] qtys, int maxLevels) {
        int i = 0;
        for (Map.Entry<Long, Deque<Order>> entry : book.entrySet()) {
            if (i == maxLevels) break;
            long levelQty = 0;
            for (Order o : entry.getValue()) {
                levelQty += o.getVisibleQty();
            }
            prices[i] = entry.getKey();
            qtys[i] = levelQty;
            i++;
        }
        return i;
    }
}
