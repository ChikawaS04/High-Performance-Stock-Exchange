package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import event.ExecutionEventType;
import model.Prices;
import model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * terminal's own JSON protocol (NEW/CANCEL out, BOOK/EXEC/SESSION in). It owns no engine internals
 * and cannot reach them; it is an ordinary client exactly like the React terminal, so its orders
 * take the same gateway, journal and engine path as the terminal's.
 *
 * <p><b>Session.</b> On start it reads the venue's session state from {@code GET /health}, then
 * follows SESSION frames. The strategy is ticked only while the session is open. When the session
 * closes, the strategy's {@link Strategy#onSessionClose} cancels are sent.
 *
 * <p><b>Stop.</b> Stopping first stops the ticks (so the strategy cannot post after its cancel
 * sweep), sends the cancels, then waits, bounded, until the venue has confirmed that nothing the
 * strategy rested is left ({@link Strategy#hasResting}), re-sending the cancels each second for
 * anything still open. Only then is the socket closed, so a bot leaves nothing behind it.
 *
 * <p><b>Threading.</b> Everything that touches strategy state or the local market view runs on
 * one single-thread {@link ScheduledExecutorService}: the periodic tick and each inbound frame's
 * dispatch (marshalled off the socket reader thread onto the same executor). That logic lives in
 * {@link Core}, which does no I/O, so it is unit-tested directly. Outbound sends are chained on a
 * {@link CompletableFuture} tail because {@link WebSocket#sendText} forbids overlapping sends.
 *
 * <p>An optional {@link MarketListener} receives every decoded BOOK/EXEC frame after the view is
 * updated; the {@link PriceTracker}'s read-only observer connection uses it.
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
    private final long tickMillis;
    private final ObjectMapper mapper;
    private final Core core;

    private final ScheduledExecutorService exec;
    private final StringBuilder textBuf = new StringBuilder();   // socket-reader thread only

    private volatile WebSocket webSocket;
    private CompletableFuture<WebSocket> sendTail;               // executor thread only

    public ParticipantClient(URI uri, String symbol, Strategy strategy, long tickMillis,
                             MarketListener listener, ObjectMapper mapper) {
        this.uri = uri;
        this.symbol = symbol;
        this.tickMillis = tickMillis;
        this.mapper = mapper;
        this.core = new Core(strategy, listener);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "participant-" + strategy.name());
            t.setDaemon(true);
            return t;
        });
    }

    /** Read the session state, connect, then start the tick loop. Blocks until the handshake completes. */
    public void start(HttpClient http) {
        boolean open = readSessionOpen(http);
        exec.execute(() -> core.setSessionOpen(open));
        this.webSocket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new Listener())
                .join();
        exec.execute(() -> this.sendTail = CompletableFuture.completedFuture(webSocket));
        exec.scheduleAtFixedRate(this::safeTick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        log.info("{} connected to {} (session {})", core.strategy.name(), uri, open ? "OPEN" : "CLOSED");
    }

    private static final long STOP_TIMEOUT_MILLIS = 6_000;
    private static final long STOP_RECANCEL_MILLIS = 1_000;
    private static final long STOP_POLL_MILLIS = 100;

    /**
     * Stop ticking, cancel everything the strategy has resting, wait (bounded) until the venue has
     * confirmed every cancel, then close the socket. Intended for one shutdown.
     */
    public void stop() {
        String name = core.strategy.name();
        try {
            exec.submit(() -> {
                core.stopping = true;
                sendAll(core.onStop());
            }).get(2, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MILLIS;
            long nextRecancel = System.currentTimeMillis() + STOP_RECANCEL_MILLIS;
            while (exec.schedule(core.strategy::hasResting, STOP_POLL_MILLIS, TimeUnit.MILLISECONDS)
                    .get(2, TimeUnit.SECONDS)) {
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    log.warn("{} stopped with orders still resting: the venue did not confirm every cancel", name);
                    break;
                }
                if (now >= nextRecancel) {
                    exec.submit(() -> sendAll(core.onStop())).get(2, TimeUnit.SECONDS);
                    nextRecancel = now + STOP_RECANCEL_MILLIS;
                }
            }
            exec.submit(() -> sendTail).get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("{} could not finish cancelling on stop: {}", name, e.toString());
        }
        exec.shutdownNow();
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // closing a half-dead socket is best-effort
            }
        }
    }

    /** {@code GET /health} on the same host and port; an unreachable or unparsable answer reads as open. */
    private boolean readSessionOpen(HttpClient http) {
        URI health = URI.create("http://" + uri.getHost() + ":" + uri.getPort() + "/health");
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(health)
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                return !"CLOSED".equals(mapper.readTree(res.body()).path("session").asText());
            }
            log.warn("{} answered HTTP {}; assuming the session is open", health, res.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not read {}: {}; assuming the session is open", health, e.toString());
        }
        return true;
    }

    // --- tick + dispatch (executor thread) -----------------------------------------------

    private void safeTick() {
        try {
            sendAll(core.onTick(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("{} tick failed: {}", core.strategy.name(), e.toString());
        }
    }

    private void dispatch(String json) {
        try {
            sendAll(core.onFrame(mapper.readTree(json)));
        } catch (Exception e) {
            log.warn("{} dropped undecodable frame: {}", core.strategy.name(), e.toString());
        }
    }

    private void sendAll(List<Action> actions) {
        for (Action a : actions) {
            send(a);
        }
    }

    private void send(Action a) {
        if (sendTail == null) {
            return;   // not connected yet
        }
        String json = encode(mapper, a, symbol);
        sendTail = sendTail.thenCompose(w -> w.sendText(json, true))
                .exceptionally(ex -> {
                    log.warn("{} send failed: {}", core.strategy.name(), ex.toString());
                    return webSocket;
                });
    }

    // --- pure core (unit-tested) ---------------------------------------------------------

    /**
     * The client's decision logic, with no socket and no executor: it folds frames into the market
     * view and the session state, and returns the actions to send. Confined to one thread.
     */
    static final class Core {
        final Strategy strategy;
        private final MarketListener listener;

        private long bestBid = Prices.NA;
        private long bestAsk = Prices.NA;
        private long lastTradeUnits = Prices.NA;
        private boolean sessionOpen;
        /** Set once by {@link ParticipantClient#stop}: no more ticks, only the stop's cancels. */
        boolean stopping;

        Core(Strategy strategy, MarketListener listener) {
            this.strategy = strategy;
            this.listener = listener;
        }

        void setSessionOpen(boolean open) {
            this.sessionOpen = open;
        }

        boolean sessionOpen() {
            return sessionOpen;
        }

        MarketView view() {
            long mid = (bestBid > 0 && bestAsk > 0) ? (bestBid + bestAsk) / 2 : Prices.NA;
            return new MarketView(bestBid, bestAsk, mid, lastTradeUnits);
        }

        List<Action> onTick(long nowMillis) {
            return (sessionOpen && !stopping) ? strategy.onTick(view(), nowMillis) : List.of();
        }

        List<Action> onStop() {
            return strategy.onSessionClose();
        }

        List<Action> onFrame(JsonNode node) {
            String type = node.path("type").asText();
            switch (type) {
                case "BOOK" -> {
                    bestBid = node.path("bestBid").asLong(Prices.NA);
                    bestAsk = node.path("bestAsk").asLong(Prices.NA);
                    if (listener != null) {
                        long mid = (bestBid > 0 && bestAsk > 0) ? (bestBid + bestAsk) / 2 : Prices.NA;
                        listener.onBook(bestBid, bestAsk, mid, node.path("timestamp").asLong());
                    }
                }
                case "EXEC" -> {
                    ExecReport r = parseExec(node);
                    if (isFill(r.execType()) && r.priceUnits() > 0) {
                        lastTradeUnits = r.priceUnits();
                    }
                    strategy.onExec(r);
                    if (listener != null) {
                        listener.onExec(r);
                    }
                }
                case "SESSION" -> {
                    boolean open = "OPEN".equals(node.path("state").asText());
                    boolean closing = sessionOpen && !open;
                    sessionOpen = open;
                    if (closing && !stopping) {
                        return strategy.onSessionClose();
                    }
                }
                default -> { }   // FIX echo frames and anything else are ignored
            }
            return List.of();
        }
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
            if (a.maxFloor() > 0) {
                n.put("maxFloor", a.maxFloor());
            }
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
                n.path("timestamp").asLong(0L),
                side(n.path("aggressorSide"), n.path("side")));
    }

    /** The first of the nodes that names a side, or null. */
    private static Side side(JsonNode... nodes) {
        for (JsonNode s : nodes) {
            if (s.isTextual()) {
                String v = s.asText();
                if ("BUY".equals(v)) return Side.BUY;
                if ("SELL".equals(v)) return Side.SELL;
            }
        }
        return null;
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
                if (!exec.isShutdown()) {   // frames still arriving after stop() have nowhere to go
                    try {
                        exec.execute(() -> dispatch(msg));
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        // stop() shut the executor down between the check and the execute
                    }
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("{} socket error: {}", core.strategy.name(), error.toString());
        }
    }
}
