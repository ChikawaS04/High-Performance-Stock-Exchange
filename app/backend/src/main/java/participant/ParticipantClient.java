package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import event.ExecutionEventType;
import model.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An external WebSocket participant: one connection to {@code ws://host:port/ws} speaking the
 * terminal's own JSON protocol (NEW/CANCEL out, BOOK/EXEC/FIX in). It owns no engine internals
 * and cannot reach them; it is an ordinary client exactly like the React terminal, so it cannot
 * perturb the hot path or the single-producer inbound invariant.
 *
 * <p><b>Threading.</b> Everything that touches strategy state or the local market view runs on
 * one single-thread {@link ScheduledExecutorService}: the periodic {@code onTick}, and each
 * inbound frame's dispatch (marshalled off the socket reader thread onto the same executor). So a
 * {@link Strategy} needs no locks, mirroring the engine's single-writer discipline. Outbound sends
 * are chained on a {@link CompletableFuture} tail because {@link WebSocket#sendText} forbids
 * overlapping sends.
 *
 * <p>An optional {@link MarketListener} receives every decoded BOOK/EXEC frame after the view is
 * updated; the {@link PriceTracker}'s read-only observer connection uses it. Trading clients pass
 * null.
 */
public final class ParticipantClient {

    private static final Logger log = LoggerFactory.getLogger(ParticipantClient.class);

    /** Passive sink for decoded frames (the tracker). Both methods run on the client executor. */
    public interface MarketListener {
        void onBook(long bestBid, long bestAsk, long midUnits, long timestamp);
        void onExec(ExecReport exec);
    }

    private final URI uri;
    private final String symbol;
    private final Strategy strategy;
    private final long tickMillis;
    private final MarketListener listener;
    private final ObjectMapper mapper;

    private final ScheduledExecutorService exec;
    private final StringBuilder textBuf = new StringBuilder();   // socket-reader thread only

    // Local market view — mutated only on the executor thread.
    private long bestBid = Prices.NA;
    private long bestAsk = Prices.NA;
    private long lastTradeUnits = Prices.NA;

    private volatile WebSocket webSocket;
    private CompletableFuture<WebSocket> sendTail;               // executor thread only

    public ParticipantClient(URI uri, String symbol, Strategy strategy, long tickMillis,
                             MarketListener listener, ObjectMapper mapper) {
        this.uri = uri;
        this.symbol = symbol;
        this.strategy = strategy;
        this.tickMillis = tickMillis;
        this.listener = listener;
        this.mapper = mapper;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "participant-" + strategy.name());
            t.setDaemon(true);
            return t;
        });
    }

    /** Connect, then start the tick loop. Blocks until the handshake completes. */
    public void start(HttpClient http) {
        this.webSocket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new Listener())
                .join();
        this.sendTail = CompletableFuture.completedFuture(webSocket);
        exec.scheduleAtFixedRate(this::safeTick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        log.info("{} connected to {}", strategy.name(), uri);
    }

    /** Stop ticking and close the socket. Idempotent-ish; intended for one shutdown. */
    public void stop() {
        exec.shutdownNow();
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException ignored) {
                // closing a half-dead socket is best-effort
            }
        }
    }

    // --- tick + dispatch (executor thread) -----------------------------------------------

    private void safeTick() {
        try {
            MarketView view = snapshotView();
            List<Action> actions = strategy.onTick(view, System.currentTimeMillis());
            for (Action a : actions) {
                send(a);
            }
        } catch (RuntimeException e) {
            log.warn("{} tick failed: {}", strategy.name(), e.toString());
        }
    }

    private MarketView snapshotView() {
        long mid = (bestBid > 0 && bestAsk > 0) ? (bestBid + bestAsk) / 2 : Prices.NA;
        return new MarketView(bestBid, bestAsk, mid, lastTradeUnits);
    }

    private void dispatch(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String type = node.path("type").asText();
            if ("BOOK".equals(type)) {
                bestBid = node.path("bestBid").asLong(Prices.NA);
                bestAsk = node.path("bestAsk").asLong(Prices.NA);
                long mid = (bestBid > 0 && bestAsk > 0) ? (bestBid + bestAsk) / 2 : Prices.NA;
                if (listener != null) {
                    listener.onBook(bestBid, bestAsk, mid, node.path("timestamp").asLong());
                }
            } else if ("EXEC".equals(type)) {
                ExecReport r = parseExec(node);
                if (isFill(r.execType()) && r.priceUnits() > 0) {
                    lastTradeUnits = r.priceUnits();
                }
                strategy.onExec(r);
                if (listener != null) {
                    listener.onExec(r);
                }
            }
            // FIX echo frames and anything else are ignored.
        } catch (Exception e) {
            log.warn("{} dropped undecodable frame: {}", strategy.name(), e.toString());
        }
    }

    // --- outbound ------------------------------------------------------------------------

    private void send(Action a) {
        String json = encode(mapper, a, symbol);
        // Chain to respect the no-overlapping-send contract; all mutation of sendTail is on exec.
        sendTail = sendTail.thenCompose(w -> w.sendText(json, true))
                .exceptionally(ex -> {
                    log.warn("{} send failed: {}", strategy.name(), ex.toString());
                    return webSocket;
                });
    }

    // --- static codec (shared with tests) ------------------------------------------------

    /** Encode an {@link Action} to the exact JSON frame the terminal protocol expects. */
    public static String encode(ObjectMapper mapper, Action a, String symbol) {
        ObjectNode n = mapper.createObjectNode();
        if (a.kind() == Action.Kind.NEW) {
            n.put("type", "NEW");
            n.put("clOrdId", a.clOrdId());
            n.put("side", a.side().name());
            n.put("ordType", a.ordType().name());
            n.put("tif", a.tif().name());
            n.put("price", a.priceUnits());
            n.put("qty", a.qty());
            n.put("symbol", symbol);
        } else {
            n.put("type", "CANCEL");
            n.put("clOrdId", a.clOrdId());
            n.put("origClOrdId", a.origClOrdId());
        }
        return n.toString();
    }

    /** Decode an EXEC node into an {@link ExecReport}. */
    public static ExecReport parseExec(JsonNode n) {
        return new ExecReport(
                ExecutionEventType.valueOf(n.path("execType").asText()),
                n.path("orderId").asLong(Prices.NA),
                n.path("tradeId").asLong(Prices.NA),
                n.path("price").asLong(Prices.NA),
                n.path("filledQuantity").asLong(Prices.NA),
                n.path("remainingQuantity").asLong(Prices.NA),
                n.path("aggressorOrderId").asLong(Prices.NA),
                n.path("passiveOrderId").asLong(Prices.NA),
                n.path("timestamp").asLong(0L));
    }

    /** A fill event carries a real trade price; accepts/cancels/rejects/expiries do not. */
    public static boolean isFill(ExecutionEventType t) {
        return t == ExecutionEventType.ORDER_FILLED || t == ExecutionEventType.ORDER_PARTIALLY_FILLED;
    }

    // --- socket listener -----------------------------------------------------------------

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                String msg = textBuf.toString();
                textBuf.setLength(0);
                exec.execute(() -> dispatch(msg));
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("{} socket error: {}", strategy.name(), error.toString());
        }
    }
}
