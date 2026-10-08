package publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import event.BookSnapshotEvent;
import event.ExecutionEvent;
import event.ExecutionEventType;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4-6. Two concerns:
 *   (1) pure JSON serialization — integer units, execType name, NA = -1, and BOOK emitting
 *       ONLY the valid [0, levelCount) prefix even when array tails are poisoned;
 *   (2) the ChannelGroup write path — a real TextWebSocketFrame lands on each embedded
 *       channel, and fan-out reaches every member.
 *
 * <p><b>EmbeddedChannel ids must be unique.</b> {@code EmbeddedChannel}'s default
 * {@code EmbeddedChannelId} has a constant hashCode and equals ANY other EmbeddedChannelId.
 * {@code DefaultChannelGroup} keys members by {@code ChannelId} and adds with
 * {@code putIfAbsent}, so a second default-constructed EmbeddedChannel is silently NOT added
 * and never receives a write. Any multi-channel test therefore constructs channels with
 * {@code new EmbeddedChannel(DefaultChannelId.newInstance())} and asserts the group size.
 *
 * <p>Writes themselves are synchronous: {@code DefaultChannelGroup.writeAndFlush} performs each
 * channel write on the calling thread (GlobalEventExecutor only aggregates the future), and
 * {@code EmbeddedEventLoop.inEventLoop()} is always true, so the frame is queued before
 * {@code onEvent} returns. {@code runPendingTasks()} is kept as belt-and-braces only.
 */
class WebSocketPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- helpers ------------------------------------------------------------------------

    /** Embedded channel with a UNIQUE id — required for ChannelGroup membership (see above). */
    private static EmbeddedChannel groupableChannel() {
        return new EmbeddedChannel(DefaultChannelId.newInstance());
    }

    private ExecutionEvent exec(ExecutionEventType type, long orderId, long tradeId, long price,
                                long filled, long remaining, long aggressor, long passive,
                                long ts) {
        ExecutionEvent e = new ExecutionEvent();
        e.eventType = type;
        e.orderId = orderId;
        e.tradeId = tradeId;
        e.price = price;
        e.filledQuantity = filled;
        e.remainingQuantity = remaining;
        e.aggressorOrderId = aggressor;
        e.passiveOrderId = passive;
        e.timestamp = ts;
        return e;
    }

    /** Fresh snapshot carrier with poisoned array tails, so any leak past the counts shows. */
    private BookSnapshotEvent snapshot() {
        BookSnapshotEvent s = new BookSnapshotEvent();
        java.util.Arrays.fill(s.bidPrices, -999L);
        java.util.Arrays.fill(s.bidQtys, -999L);
        java.util.Arrays.fill(s.askPrices, -999L);
        java.util.Arrays.fill(s.askQtys, -999L);
        return s;
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    /** Read one outbound frame's text, copying it out before releasing the frame. */
    private static String readFrameText(EmbeddedChannel ch) {
        ch.runPendingTasks();
        Object o = ch.readOutbound();
        if (o instanceof TextWebSocketFrame f) {
            try {
                return f.text();
            } finally {
                f.release();
            }
        }
        return null;
    }

    // --- (1) EXEC serialization ---------------------------------------------------------

    @Test
    void execFillSerializesAllFieldsAsIntegerUnits() throws Exception {
        WebSocketPublisher pub = new WebSocketPublisher(
                new DefaultChannelGroup(GlobalEventExecutor.INSTANCE), mapper);

        ExecutionEvent e = exec(ExecutionEventType.ORDER_FILLED,
                5L, 1L, 1_502_500L, 10L, 0L, 5L, 3L, 42L);

        JsonNode n = parse(pub.serializeExecution(e));

        assertEquals("EXEC", n.get("type").asText());
        assertEquals("ORDER_FILLED", n.get("execType").asText());
        assertEquals(5L, n.get("orderId").asLong());
        assertEquals(1L, n.get("tradeId").asLong());
        assertEquals(1_502_500L, n.get("price").asLong());   // integer units, not 150.25
        assertFalse(n.get("price").isFloatingPointNumber());
        assertEquals(10L, n.get("filledQuantity").asLong());
        assertEquals(0L, n.get("remainingQuantity").asLong());
        assertEquals(5L, n.get("aggressorOrderId").asLong());
        assertEquals(3L, n.get("passiveOrderId").asLong());
        assertEquals(42L, n.get("timestamp").asLong());
    }

    @Test
    void execAcceptCarriesMinusOneForNaFields() throws Exception {
        WebSocketPublisher pub = new WebSocketPublisher(
                new DefaultChannelGroup(GlobalEventExecutor.INSTANCE), mapper);

        // Accept: tradeId / filledQuantity / aggressor / passive are NA (-1) per the carrier.
        ExecutionEvent e = exec(ExecutionEventType.ORDER_ACCEPTED,
                7L, -1L, 1_500_000L, -1L, 25L, -1L, -1L, 99L);

        JsonNode n = parse(pub.serializeExecution(e));

        assertEquals("ORDER_ACCEPTED", n.get("execType").asText());
        assertEquals(-1L, n.get("tradeId").asLong());
        assertEquals(-1L, n.get("filledQuantity").asLong());
        assertEquals(25L, n.get("remainingQuantity").asLong());
        assertEquals(-1L, n.get("aggressorOrderId").asLong());
        assertEquals(-1L, n.get("passiveOrderId").asLong());
    }

    // --- (1) BOOK serialization ---------------------------------------------------------

    @Test
    void bookTwoSidedSerializesOnlyValidPrefixAsUnitQtyPairs() throws Exception {
        WebSocketPublisher pub = new WebSocketPublisher(
                new DefaultChannelGroup(GlobalEventExecutor.INSTANCE), mapper);

        BookSnapshotEvent s = snapshot();
        s.bidPrices[0] = 1_502_000L; s.bidQtys[0] = 50L;
        s.bidPrices[1] = 1_501_000L; s.bidQtys[1] = 30L;
        s.bidLevelCount = 2;
        s.askPrices[0] = 1_503_000L; s.askQtys[0] = 40L;
        s.askLevelCount = 1;
        s.bestBid = 1_502_000L;
        s.bestAsk = 1_503_000L;
        s.timestamp = 7L;

        JsonNode n = parse(pub.serializeSnapshot(s));

        assertEquals("BOOK", n.get("type").asText());
        assertEquals(1_502_000L, n.get("bestBid").asLong());
        assertEquals(1_503_000L, n.get("bestAsk").asLong());
        assertEquals(7L, n.get("timestamp").asLong());

        JsonNode bids = n.get("bids");
        assertEquals(2, bids.size());                       // NOT 20 — only the valid prefix
        assertEquals(1_502_000L, bids.get(0).get(0).asLong());
        assertEquals(50L, bids.get(0).get(1).asLong());
        assertEquals(1_501_000L, bids.get(1).get(0).asLong());
        assertEquals(30L, bids.get(1).get(1).asLong());

        JsonNode asks = n.get("asks");
        assertEquals(1, asks.size());
        assertEquals(1_503_000L, asks.get(0).get(0).asLong());
        assertEquals(40L, asks.get(0).get(1).asLong());

        // Poisoned tail sentinel must never appear anywhere on the wire.
        assertFalse(pub.serializeSnapshot(s).contains("-999"));
    }

    @Test
    void bookEmptySerializesMinusOneTopsAndEmptyArrays() throws Exception {
        WebSocketPublisher pub = new WebSocketPublisher(
                new DefaultChannelGroup(GlobalEventExecutor.INSTANCE), mapper);

        BookSnapshotEvent s = snapshot();
        s.bidLevelCount = 0;
        s.askLevelCount = 0;
        s.bestBid = -1L;
        s.bestAsk = -1L;
        s.timestamp = 3L;

        JsonNode n = parse(pub.serializeSnapshot(s));

        assertEquals(-1L, n.get("bestBid").asLong());
        assertEquals(-1L, n.get("bestAsk").asLong());
        assertEquals(0, n.get("bids").size());
        assertEquals(0, n.get("asks").size());
    }

    @Test
    void bookOneSidedSerializesOnlyThePopulatedSide() throws Exception {
        WebSocketPublisher pub = new WebSocketPublisher(
                new DefaultChannelGroup(GlobalEventExecutor.INSTANCE), mapper);

        BookSnapshotEvent s = snapshot();
        s.bidPrices[0] = 1_499_000L; s.bidQtys[0] = 12L;
        s.bidLevelCount = 1;
        s.askLevelCount = 0;
        s.bestBid = 1_499_000L;
        s.bestAsk = -1L;

        JsonNode n = parse(pub.serializeSnapshot(s));

        assertEquals(1, n.get("bids").size());
        assertEquals(1_499_000L, n.get("bids").get(0).get(0).asLong());
        assertEquals(0, n.get("asks").size());
        assertEquals(-1L, n.get("bestAsk").asLong());
    }

    // --- (2) ChannelGroup write path ----------------------------------------------------

    @Test
    void executionHandlerWritesTextFrameToConnectedChannel() throws Exception {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        EmbeddedChannel ch = groupableChannel();
        assertTrue(group.add(ch));

        WebSocketPublisher pub = new WebSocketPublisher(group, mapper);
        ExecutionEvent e = exec(ExecutionEventType.ORDER_FILLED,
                5L, 1L, 1_502_500L, 10L, 0L, 5L, 3L, 42L);

        pub.executionHandler().onEvent(e, 0L, true);

        String text = readFrameText(ch);
        assertNotNull(text, "expected an EXEC frame");
        JsonNode n = parse(text);
        assertEquals("EXEC", n.get("type").asText());
        assertEquals(1_502_500L, n.get("price").asLong());

        ch.finishAndReleaseAll();
    }

    @Test
    void snapshotHandlerWritesBookFrameToConnectedChannel() throws Exception {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        EmbeddedChannel ch = groupableChannel();
        assertTrue(group.add(ch));

        WebSocketPublisher pub = new WebSocketPublisher(group, mapper);
        BookSnapshotEvent s = snapshot();
        s.bidPrices[0] = 1_502_000L; s.bidQtys[0] = 50L; s.bidLevelCount = 1;
        s.bestBid = 1_502_000L; s.bestAsk = -1L;

        pub.snapshotHandler().onEvent(s, 0L, true);

        String text = readFrameText(ch);
        assertNotNull(text, "expected a BOOK frame");
        JsonNode n = parse(text);
        assertEquals("BOOK", n.get("type").asText());
        assertEquals(1, n.get("bids").size());

        ch.finishAndReleaseAll();
    }

    @Test
    void writeFansOutToEveryChannelInGroup() throws Exception {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        EmbeddedChannel a = groupableChannel();
        EmbeddedChannel b = groupableChannel();
        assertTrue(group.add(a));
        assertTrue(group.add(b));
        // Guard the EmbeddedChannelId collision trap: if ids weren't unique, size would be 1
        // and the second member would silently never receive anything.
        assertEquals(2, group.size());

        WebSocketPublisher pub = new WebSocketPublisher(group, mapper);
        ExecutionEvent e = exec(ExecutionEventType.ORDER_PARTIALLY_FILLED,
                9L, 2L, 1_498_000L, 4L, 6L, 9L, 1L, 11L);

        pub.executionHandler().onEvent(e, 0L, true);

        String textA = readFrameText(a);
        String textB = readFrameText(b);
        assertNotNull(textA, "every group member should receive the frame (A)");
        assertNotNull(textB, "every group member should receive the frame (B)");
        assertEquals("ORDER_PARTIALLY_FILLED", parse(textA).get("execType").asText());
        assertEquals("ORDER_PARTIALLY_FILLED", parse(textB).get("execType").asText());
        assertEquals(1_498_000L, parse(textB).get("price").asLong());

        a.finishAndReleaseAll();
        b.finishAndReleaseAll();
    }

    @Test
    void emptyGroupSkipsSerializationAndWritesNothing() throws Exception {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        EmbeddedChannel ch = groupableChannel();
        // Deliberately NOT added to the group — group stays empty.

        WebSocketPublisher pub = new WebSocketPublisher(group, mapper);
        ExecutionEvent e = exec(ExecutionEventType.ORDER_ACCEPTED,
                1L, -1L, 1_500_000L, -1L, 10L, -1L, -1L, 1L);

        pub.executionHandler().onEvent(e, 0L, true);

        assertTrue(group.isEmpty());
        assertNull(readFrameText(ch), "nobody connected → no frame anywhere");

        ch.finishAndReleaseAll();
    }

    // --- slot discipline ----------------------------------------------------------------

    @Test
    void frameHoldsSerializedStringNotTheReusedSlot() throws Exception {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        EmbeddedChannel ch = groupableChannel();
        assertTrue(group.add(ch));

        WebSocketPublisher pub = new WebSocketPublisher(group, mapper);
        ExecutionEvent e = exec(ExecutionEventType.ORDER_FILLED,
                5L, 1L, 1_502_500L, 10L, 0L, 5L, 3L, 42L);

        pub.executionHandler().onEvent(e, 0L, true);

        // Mutate the slot AFTER dispatch (simulating ring reuse); the frame must be unaffected.
        e.price = 99999L;
        e.orderId = 12345L;

        String text = readFrameText(ch);
        assertNotNull(text);
        JsonNode n = parse(text);
        assertEquals(1_502_500L, n.get("price").asLong());
        assertEquals(5L, n.get("orderId").asLong());

        ch.finishAndReleaseAll();
    }
}