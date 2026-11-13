package recovery;

import codec.snapshot.LocationCode;
import codec.snapshot.MessageHeaderEncoder;
import codec.snapshot.RestingOrderEncoder;
import engine.ExecutionListener;
import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import engine.Sessions;
import event.OutboundPipeline;
import model.OrdType;
import model.Order;
import model.Prices;
import model.Side;
import model.Status;
import model.TimeInForce;
import org.agrona.ExpandableDirectByteBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot body round-trips the engine's complete state (P15-5, SRS §3.10): both books with
 * exact queue order, both peg pools in FIFO order, every Order field (icebergs mid-slice and
 * below their peak, partial fills), the trade counter, both seqs and the last timestamp. The
 * restored engine then behaves identically on the next commands. Invalid bodies are rejected.
 */
class SnapshotCodecTest {

    private static long px(String dollars) {
        return Math.round(Double.parseDouble(dollars) * Prices.SCALE);
    }

    private static Order limit(long id, Side side, String price, int qty) {
        return new Order(id, id, side, qty, px(price), 1L, TimeInForce.GTC, 0, OrdType.LIMIT);
    }

    private static Order iceberg(long id, Side side, String price, int qty, int peak) {
        return new Order(id, id, side, qty, px(price), 1L, TimeInForce.GTC, peak, OrdType.LIMIT);
    }

    private static Order peg(long id, Side side, int qty) {
        return new Order(id, id, side, qty, Prices.NA, 1L, TimeInForce.GTC, 0, OrdType.PEG_MID);
    }

    /** Records every callback as a string, so two engines' outputs can be compared exactly. */
    private static final class Recorder implements ExecutionListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onFill(long aggressorOrderId, long passiveOrderId, long tradeId, long price, long filledQuantity,
                           long aggressorRemainingQuantity, Side aggressorSide, long aggressorParticipantId,
                           long passiveParticipantId) {
            events.add("FILL " + aggressorOrderId + "/" + passiveOrderId + " t" + tradeId + " " + price + " x"
                    + filledQuantity + " rem " + aggressorRemainingQuantity + " " + aggressorSide);
        }

        @Override
        public void onAccepted(long orderId, Side side, long price, long remainingQuantity) {
            events.add("ACK " + orderId + " " + side + " " + price + " " + remainingQuantity);
        }

        @Override
        public void onExpired(long orderId, long expiredQuantity) {
            events.add("EXP " + orderId + " " + expiredQuantity);
        }
    }

    /** Both lit sides, several orders per level, a partial fill, icebergs mid-slice and below peak, a bid peg pool. */
    static MatchingEngine twoSidedBook() {
        MatchingEngine e = new MatchingEngine();
        e.addOrder(limit(1, Side.SELL, "150.00", 100));
        e.addOrder(limit(2, Side.SELL, "150.00", 50));
        e.addOrder(iceberg(3, Side.SELL, "151.00", 1_000, 100));
        e.addOrder(limit(4, Side.SELL, "151.00", 40));
        e.addOrder(limit(5, Side.BUY, "149.00", 10));
        e.addOrder(limit(6, Side.BUY, "149.00", 20));
        e.addOrder(limit(7, Side.BUY, "148.00", 30));
        e.addOrder(limit(8, Side.BUY, "150.00", 30));      // order 1 left with 70 (consumed next)
        e.addOrder(limit(9, Side.BUY, "151.00", 250));     // 120 at 150.00, 100 of 3's tip (reloads behind 4), 30 of 4
        e.addOrder(limit(14, Side.BUY, "151.00", 30));     // 4's last 10, then 20 of 3's new tip: 3 is mid-slice
        e.addOrder(iceberg(10, Side.BUY, "149.50", 120, 100));
        e.addOrder(limit(11, Side.SELL, "149.50", 100));   // takes 10's tip: 20 left, below its peak
        e.addOrder(limit(15, Side.BUY, "149.80", 50));
        e.addOrder(limit(16, Side.SELL, "149.80", 20));    // plain partial fill: 15 left with 30
        e.addOrder(peg(12, Side.BUY, 5));
        e.addOrder(peg(13, Side.BUY, 7));
        return e;
    }

    /** One lit side only (no midpoint), so both peg pools rest inactive. */
    static MatchingEngine oneSidedBookWithBothPegPools() {
        MatchingEngine e = new MatchingEngine();
        e.addOrder(limit(1, Side.SELL, "150.00", 100));
        e.addOrder(limit(2, Side.SELL, "151.00", 50));
        e.addOrder(peg(3, Side.BUY, 10));
        e.addOrder(peg(4, Side.SELL, 20));
        e.addOrder(peg(5, Side.BUY, 30));
        e.addOrder(peg(6, Side.SELL, 40));
        return e;
    }

    private static int encode(SnapshotCodec codec, ExpandableDirectByteBuffer buffer, MatchingEngine engine) {
        return codec.encode(buffer, engine, 42L, 4_096L, 7L, 99L, 123_456_789L);
    }

    private static MatchingEngine restoreInto(RestoredSnapshot snapshot, Recorder recorder) {
        MatchingEngine engine = new MatchingEngine();
        MatchingEngineHandler handler = new MatchingEngineHandler(engine, new OutboundPipeline().getRingBuffer());
        snapshot.applyTo(engine, handler);
        assertEquals(snapshot.outboundLastSeq(), handler.getLastSeq());
        assertEquals(snapshot.lastCommandTimestamp(), handler.getCommandTimestamp());
        engine.setExecutionListener(recorder);
        return engine;
    }

    private void assertRoundTrip(MatchingEngine original, List<Order> nextCommands, List<Order> sameCommandsAgain)
            throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);   // forces growth
        int length = encode(new SnapshotCodec(), buffer, original);
        RestoredSnapshot snapshot = SnapshotCodec.decode(buffer, length);

        assertEquals(42L, snapshot.inboundSeq());
        assertEquals(4_096L, snapshot.inboundPosition());
        assertEquals(7L, snapshot.inboundRecordingId());
        assertEquals(99L, snapshot.outboundLastSeq());
        assertEquals(original.getNextTradeId(), snapshot.nextTradeId());
        assertEquals(123_456_789L, snapshot.lastCommandTimestamp());

        Recorder restoredEvents = new Recorder();
        MatchingEngine restored = restoreInto(snapshot, restoredEvents);
        assertEquals(EngineStates.describe(original), EngineStates.describe(restored));

        Recorder originalEvents = new Recorder();
        original.setExecutionListener(originalEvents);
        for (Order o : nextCommands) original.addOrder(o);
        for (Order o : sameCommandsAgain) restored.addOrder(o);
        assertTrue(originalEvents.events.size() > 2, "the next commands trade against the restored book");
        assertEquals(originalEvents.events, restoredEvents.events, "identical events after restore");
        assertEquals(EngineStates.describe(original), EngineStates.describe(restored));
    }

    @Test
    void aTwoSidedBookWithIcebergsPartialFillsAndAPegPoolRoundTrips() throws Exception {
        MatchingEngine original = twoSidedBook();
        List<String> state = EngineStates.describe(original);
        assertTrue(state.stream().anyMatch(s -> s.contains("id=3 ") && s.contains("status=PARTIALLY_FILLED")
                && !s.contains("vis=100")), "iceberg 3 is mid-slice: " + state);
        assertTrue(state.stream().anyMatch(s -> s.contains("id=10 ") && s.contains("qty=20") && s.contains("peak=100")),
                "iceberg 10 is below its peak: " + state);
        assertTrue(state.stream().anyMatch(s -> s.contains("id=15 ") && s.contains("qty=30")
                && s.contains("status=PARTIALLY_FILLED")), "plain order 15 partly filled: " + state);

        assertRoundTrip(original,
                List.of(limit(20, Side.SELL, "148.00", 200), limit(21, Side.BUY, "152.00", 2_000)),
                List.of(limit(20, Side.SELL, "148.00", 200), limit(21, Side.BUY, "152.00", 2_000)));
    }

    @Test
    void bothPegPoolsRoundTripInFifoOrderAndCrossIdentically() throws Exception {
        // A bid resting onto the empty side creates a mid and crosses the pools head against head.
        assertRoundTrip(oneSidedBookWithBothPegPools(),
                List.of(limit(20, Side.BUY, "149.00", 5), peg(21, Side.SELL, 15)),
                List.of(limit(20, Side.BUY, "149.00", 5), peg(21, Side.SELL, 15)));
    }

    @Test
    void anEmptyEngineRoundTrips() throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        int length = encode(new SnapshotCodec(), buffer, new MatchingEngine());
        RestoredSnapshot snapshot = SnapshotCodec.decode(buffer, length);
        assertTrue(snapshot.orders().isEmpty());
        assertEquals(1L, snapshot.nextTradeId());
    }

    @Test
    void restoreBuildsOneObjectGraph() throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        int length = encode(new SnapshotCodec(), buffer, twoSidedBook());
        RestoredSnapshot snapshot = SnapshotCodec.decode(buffer, length);
        MatchingEngine restored = restoreInto(snapshot, new Recorder());

        for (RestoredSnapshot.RestoredOrder entry : snapshot.orders()) {
            Order instance = entry.order();
            List<Order> seen = new ArrayList<>();
            restored.visitResting((o, pegged) -> { if (o.getOrderID() == instance.getOrderID()) seen.add(o); });
            assertEquals(1, seen.size());
            assertSame(instance, seen.get(0), "the queue holds the restored instance");
        }
        Order three = snapshot.orders().stream().map(RestoredSnapshot.RestoredOrder::order)
                .filter(o -> o.getOrderID() == 3L).findFirst().orElseThrow();
        assertTrue(restored.cancelOrder(3L));
        assertEquals(Status.CANCELLED, three.getStatus(), "openOrders resolved to the same instance");
        restored.visitResting((o, pegged) -> assertTrue(o.getOrderID() != 3L, "cancel removed it from its queue"));
    }

    @Test
    void restoreRefusesAnEngineThatIsNotPristine() throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        int length = encode(new SnapshotCodec(), buffer, twoSidedBook());
        RestoredSnapshot snapshot = SnapshotCodec.decode(buffer, length);
        MatchingEngine used = new MatchingEngine();
        used.addOrder(limit(1, Side.BUY, "100.00", 1));
        assertThrows(IllegalStateException.class,
                () -> snapshot.applyTo(used, new MatchingEngineHandler(used, new OutboundPipeline().getRingBuffer())));
    }

    // --- invalid bodies ---

    private static ExpandableDirectByteBuffer encoded(int[] lengthOut) {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        lengthOut[0] = encode(new SnapshotCodec(), buffer, twoSidedBook());
        return buffer;
    }

    private static int orderOffset(int i) {
        return SnapshotCodec.HEADER_LENGTH + i * SnapshotCodec.ORDER_LENGTH;
    }

    private static RestingOrderEncoder orderAt(ExpandableDirectByteBuffer buffer, int i) {
        return new RestingOrderEncoder().wrap(buffer, orderOffset(i) + MessageHeaderEncoder.ENCODED_LENGTH);
    }

    private static void assertInvalid(ExpandableDirectByteBuffer buffer, int length, String reasonPart) {
        InvalidSnapshotException e = assertThrows(InvalidSnapshotException.class,
                () -> SnapshotCodec.decode(buffer, length));
        assertTrue(e.getMessage().contains(reasonPart), e.getMessage());
    }

    // --- session state (P15-8, schema version 1) ---

    @Test
    void theSessionStateRoundTripsAndIsRestoredIntoTheHandler() throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        int length = new SnapshotCodec().encode(buffer, twoSidedBook(), 42L, 4_096L, 7L, 99L, 123L,
                true, 20261102, 20261030, Sessions.symbol("BRK.B"));
        RestoredSnapshot snapshot = SnapshotCodec.decode(buffer, length);
        assertTrue(snapshot.hasSessionState());
        assertTrue(snapshot.sessionOpen());
        assertEquals(20261102, snapshot.businessDate());
        assertEquals(20261030, snapshot.lastClosedBusinessDate());
        assertArrayEquals(Sessions.symbol("BRK.B"), snapshot.symbol());

        MatchingEngine engine = new MatchingEngine();
        MatchingEngineHandler handler = new MatchingEngineHandler(engine, new OutboundPipeline().getRingBuffer());
        snapshot.applyTo(engine, handler);
        assertTrue(handler.isSessionOpen());
        assertEquals(20261102, handler.getBusinessDate());
        assertEquals(20261030, handler.getLastClosedBusinessDate());
        byte[] symbol = new byte[8];
        handler.getSessionSymbol(symbol);
        assertArrayEquals(Sessions.symbol("BRK.B"), symbol);

        int closedLength = new SnapshotCodec().encode(buffer, new MatchingEngine(), 42L, 4_096L, 7L, 99L, 123L,
                false, -1, 20261102, Sessions.symbol());
        RestoredSnapshot closed = SnapshotCodec.decode(buffer, closedLength);
        assertFalse(closed.sessionOpen());
        assertEquals(-1, closed.businessDate());
        assertEquals(20261102, closed.lastClosedBusinessDate());
    }

    @Test
    void aVersionZeroSnapshotDecodesAsNeverOpened() throws Exception {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        new SnapshotCodec().encode(buffer, new MatchingEngine(), 42L, 4_096L, 7L, 99L, 123L,
                true, 20261102, -1, Sessions.symbol());
        // A P15-5 snapshot: version 0, its root block only the seven int64 fields, no orders.
        int v0Block = 7 * Long.BYTES;
        new MessageHeaderEncoder().wrap(buffer, 0).version(0).blockLength(v0Block);
        RestoredSnapshot snapshot = SnapshotCodec.decode(buffer, MessageHeaderEncoder.ENCODED_LENGTH + v0Block);
        assertFalse(snapshot.hasSessionState());
        assertFalse(snapshot.sessionOpen());
        assertEquals(-1, snapshot.businessDate());
        assertEquals(-1, snapshot.lastClosedBusinessDate());
        assertArrayEquals(new byte[8], snapshot.symbol());
        assertEquals(42L, snapshot.inboundSeq(), "the version 0 fields still decode");
    }

    @Test
    void aSessionStateNoHandlerCouldReachIsRejected() {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(64);
        SnapshotCodec codec = new SnapshotCodec();
        int length = codec.encode(buffer, new MatchingEngine(), 1L, 0L, 7L, 0L, 0L, true, 20261332, -1, Sessions.symbol());
        assertInvalid(buffer, length, "business date 20261332");
        length = codec.encode(buffer, new MatchingEngine(), 1L, 0L, 7L, 0L, 0L, true, 20261102, 20261102, Sessions.symbol());
        assertInvalid(buffer, length, "not after the last closed date");
        length = codec.encode(buffer, new MatchingEngine(), 1L, 0L, 7L, 0L, 0L, true, 20261102, -1, new byte[8]);
        assertInvalid(buffer, length, "symbol");
        length = codec.encode(buffer, new MatchingEngine(), 1L, 0L, 7L, 0L, 0L, false, -1, -1, Sessions.symbol());
        assertInvalid(buffer, length, "symbol");
    }

    @Test
    void aNewerVersionIsRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        new MessageHeaderEncoder().wrap(buffer, 0).version(MessageHeaderEncoder.SCHEMA_VERSION + 1);
        assertInvalid(buffer, len[0], "newer");
    }

    @Test
    void aForeignSchemaIsRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        new MessageHeaderEncoder().wrap(buffer, 0).schemaId(1);
        assertInvalid(buffer, len[0], "foreign schema");
    }

    @Test
    void aTruncatedBodyIsRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        assertInvalid(buffer, len[0] - SnapshotCodec.ORDER_LENGTH, "order count");
        assertInvalid(buffer, SnapshotCodec.HEADER_LENGTH - 1, "shorter");
    }

    @Test
    void trailingBytesAreRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        assertInvalid(buffer, len[0] + 8, "follow the last order");
    }

    @Test
    void aDuplicateOrderIdIsRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        orderAt(buffer, 1).orderId(new codec.snapshot.RestingOrderDecoder()
                .wrap(buffer, orderOffset(0) + MessageHeaderEncoder.ENCODED_LENGTH,
                        RestingOrderEncoder.BLOCK_LENGTH, 0).orderId());
        assertInvalid(buffer, len[0], "duplicate order id");
    }

    @Test
    void ordersOutOfRestoreOrderAreRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        orderAt(buffer, 0).location(LocationCode.PEG_BID);   // a bid level after the peg pool
        assertInvalid(buffer, len[0], "");
    }

    @Test
    void litLevelsOutOfPriceOrderAreRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        orderAt(buffer, 0).price(px("100.00"));   // first (best) bid now below the next level
        assertInvalid(buffer, len[0], "price order");
    }

    @Test
    void anUnknownEnumValueIsRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        buffer.putByte(orderOffset(0) + MessageHeaderEncoder.ENCODED_LENGTH
                + RestingOrderEncoder.statusEncodingOffset(), (byte) 77);
        assertInvalid(buffer, len[0], "Unknown value");
    }

    @Test
    void anOrderThatCannotRestIsRejected() {
        int[] len = new int[1];
        ExpandableDirectByteBuffer buffer = encoded(len);
        orderAt(buffer, 0).visibleQty(0);
        assertInvalid(buffer, len[0], "Visible quantity");
    }
}
