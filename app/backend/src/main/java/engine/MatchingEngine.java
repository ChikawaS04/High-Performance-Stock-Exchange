package engine;

import event.BookSnapshotEvent;
import model.Order;
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

    private void matchBuy(Order buyOrder) {
        while (buyOrder.getQuantity() > 0 && !asks.isEmpty()) {
            Map.Entry<Long, Deque<Order>> bestAskEntry  = asks.firstEntry();
            long askPrice = bestAskEntry.getKey();

            if (!marketable(Side.BUY, buyOrder.getPrice(), askPrice)) break;

            Deque<Order> bestAskQueue = bestAskEntry.getValue();
            Order askOrder = bestAskQueue.getFirst();

            int quantityMatched = Math.min(buyOrder.getQuantity(), askOrder.getQuantity());
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

            int quantityMatched = Math.min(sellOrder.getQuantity(), bidOrder.getQuantity());
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
     * <p>At this phase the eligible liquidity is the lit book only. Iceberg reserve needs no
     * special handling here: getQuantity() is already an order's TOTAL remaining size, so a
     * resting iceberg's hidden reserve is counted once that type exists. The midpoint peg pool
     * term is added in a later phase by extending this method, not rewriting it.
     *
     * <p>Allocation: the enhanced-for loops allocate map/deque iterators that do not escape,
     * the same already-documented book-structure allocation as snapshotInto; measured, not
     * assumed away, at the Phase 14 benchmark re-measure.
     */
    private boolean canFillCompletely(Order order) {
        long needed = order.getQuantity();
        TreeMap<Long, Deque<Order>> contra = (order.getSide() == Side.BUY) ? asks : bids;

        long available = 0;
        for (Map.Entry<Long, Deque<Order>> level : contra.entrySet()) {
            if (!marketable(order.getSide(), order.getPrice(), level.getKey())) break;
            for (Order resting : level.getValue()) {
                available += resting.getQuantity();
                if (available >= needed) return true;
            }
        }
        return available >= needed;
    }

    /**
     * Admits one order: match against the resting book, then dispose of any unfilled
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

        if (tif == TimeInForce.FOK && !canFillCompletely(order)) {
            executionListener.onExpired(order.getOrderID(), order.getQuantity());
            return;
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
    }

    private void addToBook(TreeMap<Long, Deque<Order>> book, Order order) {
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

        TreeMap<Long, Deque<Order>> book = (order.getSide() == Side.BUY) ? bids : asks;
        Deque<Order> queue = book.get(order.getPrice());
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) book.remove(order.getPrice());
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
            int totalQty = entry.getValue().stream().mapToInt(Order::getQuantity).sum();
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
            int totalQty = entry.getValue().stream().mapToInt(Order::getQuantity).sum();
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
     * iteration order is already best→worst), aggregating total resting quantity per price
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
     * {@code book}, aggregating resting quantity per price level.
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
                levelQty += o.getQuantity();
            }
            prices[i] = entry.getKey();
            qtys[i] = levelQty;
            i++;
        }
        return i;
    }
}
