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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * An external WebSocket participant: one connection to {@code ws://host:port/ws} speaking the
 * terminal's own JSON protocol (NEW/CANCEL/RESEND out, BOOK/EXEC/SESSION/RESEND_* in). It owns no
 * engine internals and cannot reach them; it is an ordinary client exactly like the React
 * terminal, so its orders take the same gateway, journal and engine path as the terminal's.
 *
 * <p><b>Connecting (P16-5).</b> Every attempt first reads {@code GET /health}; a connection is made
 * only once it answers 200, so a venue that cannot be read is never assumed open. Attempts back off
 * from {@link #BACKOFF_MIN_MILLIS} doubling to {@link #BACKOFF_MAX_MILLIS}, reset once the client is
 * live again. A lost connection logs one WARN; further failed attempts log at DEBUG; the reconnect
 * logs one INFO.
 *
 * <p><b>Catching up.</b> EXEC and SESSION frames carry the venue's gap-free seq. After a reconnect
 * the client sends {@code RESEND} from its last applied seq + 1 and applies the replay and the live
 * stream through a {@link SeqTracker} (dedupe by seq, in seq order). While it catches up nothing is
 * sent and the strategy is not ticked; frames still fold into the view, the session state and the
 * strategy's book. Once the gap is closed it cancels every order whose fate is uncertain
 * ({@link Strategy#onResync}), or everything if the session closed meanwhile, then trades again. If
 * the venue's {@code /health} shows fewer events than the client has applied, the venue lost its
 * history: the client starts a new baseline and cancels everything it thought was resting (each
 * unknown order comes back ORDER_REJECTED and is forgotten).
 *
 * <p><b>Session.</b> The strategy is ticked only while the session is open. When the session
 * closes, the strategy's {@link Strategy#onSessionClose} cancels are sent.
 *
 * <p><b>Risk.</b> Every NEW passes the bot's {@link RiskGate} before it is sent; a refused one is
 * handed back to the strategy as a local reject. One WARN at the first refusal, then at most one a
 * minute with the count.
 *
 * <p><b>Stop.</b> {@link #stopAll} stops every client together: no more ticks, the cancels sent,
 * one shared bounded wait for the venue to confirm them (re-sending each second), then the sockets
 * are closed. A SIGTERM therefore leaves nothing behind within about 4 s.
 *
 * <p><b>Threading.</b> Everything that touches strategy state, the view or the connection state
 * runs on one single-thread {@link ScheduledExecutorService}: the periodic tick, each inbound
 * frame (marshalled off the socket reader thread), every connection step (the HTTP and WebSocket
 * futures post back to it). That logic lives in {@link Core}, which does no I/O, so it is
 * unit-tested directly. Outbound sends are chained on a {@link CompletableFuture} tail because
 * {@link WebSocket#sendText} forbids overlapping sends.
 */
public final class ParticipantClient {

    private static final Logger log = LoggerFactory.getLogger(ParticipantClient.class);

    static final long BACKOFF_MIN_MILLIS = 250;
    static final long BACKOFF_MAX_MILLIS = 5_000;
    /** RESEND requests per reconnect before the connection is dropped and the reconnect starts over. */
    static final int MAX_RESEND_TRIES = 5;
    static final long REFUSAL_WARN_INTERVAL_MILLIS = 60_000;

    /** Passive sink for decoded frames (the tracker). Both methods run on the client executor. */
    public interface MarketListener {
        void onBook(long bestBid, long bestAsk, long midUnits, long timestamp);
        void onExec(ExecReport exec);
    }

    /** Called on the client executor for each SESSION OPENED applied while live (never while catching up). */
    public interface SessionOpenListener {
        void onSessionOpened(ParticipantClient client);
    }

    /** Where the connection is. */
    enum Phase { CONNECTING, RESYNCING, LIVE }

    private final URI wsUri;
    private final URI healthUri;
    private final String symbol;
    private final long tickMillis;
    private final ObjectMapper mapper;
    private final Core core;
    private final ScheduledExecutorService exec;
    private final WarnLimiter refusalWarn = new WarnLimiter(REFUSAL_WARN_INTERVAL_MILLIS, System::currentTimeMillis);

    private volatile SessionOpenListener sessionOpenListener;
    private volatile boolean closed;

    // Executor thread only.
    private HttpClient http;
    private WebSocket webSocket;
    private CompletableFuture<WebSocket> sendTail;
    private int generation;
    private long backoff = BACKOFF_MIN_MILLIS;
    private int failedAttempts;
    private int resendTries;
    private boolean everConnected;
    private long sentNew;
    private long sentCancel;
    private long reconnects;

    /**
     * @param gate the bot's risk gate; null for a connection that never sends (the observer)
     */
    public ParticipantClient(URI wsUri, URI healthUri, String symbol, Strategy strategy, RiskGate gate,
                             long tickMillis, MarketListener listener, ObjectMapper mapper) {
        this.wsUri = wsUri;
        this.healthUri = healthUri;
        this.symbol = symbol;
        this.tickMillis = tickMillis;
        this.mapper = mapper;
        this.core = new Core(strategy, gate, listener);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "participant-" + strategy.name());
            t.setDaemon(true);
            return t;
        });
    }

    public String name() {
        return core.strategy.name();
    }

    public void setSessionOpenListener(SessionOpenListener l) {
        this.sessionOpenListener = l;
    }

    /** Start connecting (in the background) and start the tick loop. Returns at once. */
    public void start(HttpClient http) {
        exec.execute(() -> {
            this.http = http;
            connect(generation);
        });
        exec.scheduleAtFixedRate(this::safeTick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    /** Run {@code task} on the client executor (for the anchor refresh's answer). */
    public void post(Runnable task) {
        try {
            exec.execute(task);
        } catch (RejectedExecutionException ignored) {
            // stopped
        }
    }

    /** The strategy, for code that runs on the client executor via {@link #post}. */
    Strategy strategy() {
        return core.strategy;
    }

    /** One status line's worth of numbers, read on the executor (bounded). */
    public String status() {
        try {
            return exec.submit(() -> {
                RiskGate g = core.gate;
                return name() + " phase=" + core.phase + " session=" + (core.sessionOpen ? "OPEN" : "CLOSED")
                        + (g == null ? "" : " position=" + g.position() + " limit=" + g.risk().maxPosition()
                                + " open=" + g.openOrders() + " refused=" + g.refused())
                        + " sentNew=" + sentNew + " sentCancel=" + sentCancel + " reconnects=" + reconnects
                        + " lastSeq=" + core.seqs.lastApplied();
            }).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return name() + " status unavailable";
        }
    }

    // --- connection (executor thread) --------------------------------------------------------

    private void connect(int gen) {
        if (closed || gen != generation) {
            return;
        }
        HttpRequest req = HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(5)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((res, err) -> post(() -> onHealth(gen, res, err)));
    }

    private void onHealth(int gen, HttpResponse<String> res, Throwable err) {
        if (closed || gen != generation) {
            return;
        }
        if (err != null || res.statusCode() != 200) {
            attemptFailed(err != null ? "GET /health: " + err : "GET /health answered HTTP " + res.statusCode());
            return;
        }
        JsonNode h;
        try {
            h = mapper.readTree(res.body());
        } catch (Exception e) {
            attemptFailed("GET /health: unreadable body");
            return;
        }
        core.onHealth("OPEN".equals(h.path("session").asText()), h.path("publishedOutboundSeq").asLong(-1));
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsUri, new Listener(gen))
                .whenComplete((ws, e) -> post(() -> onOpened(gen, ws, e)));
    }

    private void onOpened(int gen, WebSocket ws, Throwable err) {
        if (closed || gen != generation) {
            if (ws != null) ws.abort();
            return;
        }
        if (err != null) {
            attemptFailed("WebSocket: " + err);
            return;
        }
        webSocket = ws;
        sendTail = CompletableFuture.completedFuture(ws);
        Core.Connected c = core.onConnected();
        if (everConnected) {
            reconnects++;
            log.info("{} reconnected to {} after {} failed attempt(s); {}", name(), wsUri, failedAttempts,
                    c.resendFrom() > 0 ? "RESEND from seq " + c.resendFrom() : "no catch-up needed");
        } else {
            log.info("{} connected to {} (session {})", name(), wsUri, core.sessionOpen ? "OPEN" : "CLOSED");
        }
        everConnected = true;
        failedAttempts = 0;
        if (c.venueReset()) {
            log.warn("{}: the venue reports fewer events than this bot applied; it lost its history. "
                    + "Starting a new baseline and cancelling everything this bot thought was resting", name());
        }
        if (c.resendFrom() > 0) {
            resendTries = 1;
            sendResend(c.resendFrom());
        } else {
            backoff = BACKOFF_MIN_MILLIS;
        }
        sendAll(c.actions());
    }

    /** A connect attempt failed: back off and try again. Logged at WARN only for the very first start. */
    private void attemptFailed(String why) {
        failedAttempts++;
        if (!everConnected && failedAttempts == 1) {
            log.warn("{} cannot reach the venue yet ({}); retrying with back-off up to {} ms", name(), why,
                    BACKOFF_MAX_MILLIS);
        } else {
            log.debug("{} connect attempt {} failed: {}", name(), failedAttempts, why);
        }
        scheduleConnect();
    }

    private void scheduleConnect() {
        long delay = backoff;
        backoff = Math.min(backoff * 2, BACKOFF_MAX_MILLIS);
        int next = ++generation;
        try {
            exec.schedule(() -> connect(next), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // stopped
        }
    }

    /** The socket for {@code gen} closed or failed. One WARN, then reconnect. */
    private void onDisconnected(int gen, String why) {
        if (closed || gen != generation) {
            return;
        }
        log.warn("{} lost its connection to {} ({}); reconnecting", name(), wsUri, why);
        WebSocket ws = webSocket;
        webSocket = null;
        sendTail = null;
        if (ws != null) ws.abort();
        core.onDisconnected();
        scheduleConnect();
    }

    private void onResendComplete(int gen) {
        if (gen != generation || core.phase != Phase.RESYNCING) {
            return;
        }
        Core.Resync r = core.onResendComplete();
        if (r.done()) {
            log.info("{} caught up through seq {}; trading resumes", name(), core.seqs.lastApplied());
            backoff = BACKOFF_MIN_MILLIS;
            resendTries = 0;
            sendAll(r.actions());
        } else {
            retryResend(gen, "a gap is left after RESEND_COMPLETE");
        }
    }

    private void retryResend(int gen, String why) {
        if (gen != generation || core.phase != Phase.RESYNCING) {
            return;
        }
        if (resendTries >= MAX_RESEND_TRIES) {
            onDisconnected(gen, "could not catch up after " + resendTries + " RESEND requests (" + why + ")");
            return;
        }
        resendTries++;
        log.debug("{} RESEND again ({}), try {}", name(), why, resendTries);
        try {
            exec.schedule(() -> {
                if (gen == generation && core.phase == Phase.RESYNCING) {
                    sendResend(core.seqs.next());
                }
            }, backoff, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // stopped
        }
    }

    // --- tick + dispatch (executor thread) -----------------------------------------------

    private void safeTick() {
        try {
            sendAll(core.onTick(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("{} tick failed: {}", name(), e.toString());
        }
    }

    private void dispatch(int gen, String json) {
        if (gen != generation) {
            return;   // a frame from a connection already given up
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("{} dropped undecodable frame: {}", name(), e.toString());
            return;
        }
        String type = node.path("type").asText();
        if ("RESEND_COMPLETE".equals(type)) {
            onResendComplete(gen);
            return;
        }
        if ("RESEND_REJECTED".equals(type)) {
            retryResend(gen, "RESEND_REJECTED " + node.path("reason").asText());
            return;
        }
        try {
            sendAll(core.onFrame(node));
        } catch (RuntimeException e) {
            log.warn("{} could not apply a frame: {}", name(), e.toString());
        }
        if (core.takeSessionOpened()) {
            SessionOpenListener l = sessionOpenListener;
            if (l != null) {
                l.onSessionOpened(this);
            }
        }
    }

    private void sendAll(List<Action> actions) {
        for (Action a : actions) {
            send(a);
        }
        long refusals = core.takeRefusals();
        for (long i = 0; i < refusals; i++) {
            long held = refusalWarn.occurrence();
            if (held >= 0) {
                log.warn("{} risk gate refused a NEW ({}); {} refused in all{}", name(), core.lastRefusal,
                        core.gate.refused(), held > 0 ? ", " + held + " more since the last warning" : "");
            }
        }
    }

    private void send(Action a) {
        if (sendTail == null) {
            return;   // not connected
        }
        if (a.kind() == Action.Kind.NEW) sentNew++; else sentCancel++;
        sendText(encode(mapper, a, symbol));
    }

    private void sendResend(long fromSeq) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "RESEND");
        n.put("fromSeq", fromSeq);
        sendText(n.toString());
    }

    private void sendText(String json) {
        if (sendTail == null) {
            return;
        }
        int gen = generation;
        sendTail = sendTail.thenCompose(w -> w.sendText(json, true))
                .exceptionally(ex -> {
                    post(() -> onDisconnected(gen, "send failed: " + ex));
                    return null;
                });
    }

    // --- stop -----------------------------------------------------------------------------

    private <T> T call(Callable<T> task, T otherwise, long timeoutMillis) {
        try {
            return exec.submit(task).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return otherwise;
        }
    }

    /** Phase 1 of a stop: no more ticks, send the cancels. */
    private void beginStop() {
        post(() -> {
            core.stopping = true;
            sendAll(core.onStop());
        });
    }

    /** Still waiting for the venue: connected and something rested is not yet confirmed gone. */
    private boolean awaitingCancels() {
        return call(() -> core.phase == Phase.LIVE && sendTail != null && core.strategy.hasResting(), false, 500);
    }

    private void recancel() {
        post(() -> sendAll(core.onStop()));
    }

    /** Flush what is queued and send a close frame; returns the future of both. */
    private CompletableFuture<?> beginClose() {
        closed = true;
        CompletableFuture<?> done = call(() -> {
            CompletableFuture<WebSocket> tail = sendTail;
            WebSocket ws = webSocket;
            if (tail == null || ws == null) {
                return CompletableFuture.completedFuture(null);
            }
            return tail.thenCompose(w -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"));
        }, CompletableFuture.completedFuture(null), 500);
        return done;
    }

    private void shutdownNow() {
        exec.shutdownNow();
        WebSocket ws = webSocket;   // a racy read is fine: abort is idempotent
        if (ws != null) ws.abort();
    }

    /**
     * Stop every client together (SIGTERM, P16-5): each stops ticking and sends its cancels at
     * once; one shared wait of at most {@code ackWaitMillis} for the venue to confirm them,
     * re-sending each second; then each socket gets at most {@code closeMillis} to flush and close.
     * Bounded at about {@code ackWaitMillis + closeMillis + 1 s} however many clients there are.
     */
    public static void stopAll(List<ParticipantClient> clients, long ackWaitMillis, long closeMillis) {
        long start = System.currentTimeMillis();
        for (ParticipantClient c : clients) {
            c.beginStop();
        }
        long deadline = start + ackWaitMillis;
        long nextRecancel = start + 1_000;
        List<ParticipantClient> waiting = new ArrayList<>(clients);
        while (true) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            waiting.removeIf(c -> !c.awaitingCancels());
            long now = System.currentTimeMillis();
            if (waiting.isEmpty()) {
                log.info("Every bot's resting orders are confirmed cancelled ({} ms)", now - start);
                break;
            }
            if (now >= deadline) {
                List<String> names = new ArrayList<>();
                for (ParticipantClient c : waiting) names.add(c.name());
                log.warn("Stopping with orders the venue has not confirmed cancelled: {}", names);
                break;
            }
            if (now >= nextRecancel) {
                for (ParticipantClient c : waiting) c.recancel();
                nextRecancel = now + 1_000;
            }
        }
        List<CompletableFuture<?>> closing = new ArrayList<>();
        for (ParticipantClient c : clients) {
            closing.add(c.beginClose());
        }
        try {
            CompletableFuture.allOf(closing.toArray(new CompletableFuture[0])).get(closeMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // a close that does not finish in time is abandoned below
        }
        for (ParticipantClient c : clients) {
            c.shutdownNow();
        }
    }

    // --- pure core (unit-tested) ---------------------------------------------------------

    /**
     * The client's decision logic, with no socket and no executor: it folds frames into the market
     * view, the session state and the strategy, decides what a (re)connection must do, and filters
     * what is sent through the risk gate. Confined to one thread.
     */
    static final class Core {
        final Strategy strategy;
        final RiskGate gate;
        private final MarketListener listener;
        final SeqTracker<JsonNode> seqs = new SeqTracker<>();

        Phase phase;
        private long bestBid = Prices.NA;
        private long bestAsk = Prices.NA;
        private long lastTradeUnits = Prices.NA;
        boolean sessionOpen;
        private boolean healthOpen;
        private long healthPublished = -1;
        private boolean sessionOpenedLive;
        private long refusalsPending;
        RiskGate.Refusal lastRefusal;
        /** Set once by a stop: no more ticks, only the stop's cancels. */
        boolean stopping;

        /** A connected core with no gate, already live: the pre-P16-5 behaviour, kept for tests. */
        Core(Strategy strategy, MarketListener listener) {
            this(strategy, null, listener);
            this.phase = Phase.LIVE;
        }

        Core(Strategy strategy, RiskGate gate, MarketListener listener) {
            this.strategy = strategy;
            this.gate = gate;
            this.listener = listener;
            this.phase = Phase.CONNECTING;
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

        /** What {@code /health} said just before this connection attempt. */
        void onHealth(boolean open, long publishedSeq) {
            this.healthOpen = open;
            this.healthPublished = publishedSeq;
        }

        /** What to do once the socket is open: RESEND from a seq, or go live with these actions. */
        record Connected(long resendFrom, boolean venueReset, List<Action> actions) { }

        Connected onConnected() {
            sessionOpen = healthOpen;
            if (!seqs.hasBaseline()) {
                phase = Phase.LIVE;   // a fresh bot: the first sequenced frame sets the baseline
                return new Connected(0, false, goLive());
            }
            if (healthPublished >= 0 && healthPublished < seqs.lastApplied()) {
                seqs.reset();
                phase = Phase.LIVE;
                return new Connected(0, true, stopping ? List.of() : filter(strategy.onSessionClose()));
            }
            phase = Phase.RESYNCING;
            return new Connected(seqs.next(), false, List.of());
        }

        void onDisconnected() {
            phase = Phase.CONNECTING;
        }

        /** Whether the catch-up is complete, and what to send if it is. */
        record Resync(boolean done, List<Action> actions) { }

        Resync onResendComplete() {
            if (phase != Phase.RESYNCING) {
                return new Resync(true, List.of());
            }
            if (seqs.hasGap()) {
                return new Resync(false, List.of());
            }
            phase = Phase.LIVE;
            return new Resync(true, goLive());
        }

        /** Entering LIVE: settle every order whose fate is uncertain. */
        private List<Action> goLive() {
            if (stopping) {
                return List.of();
            }
            if (!sessionOpen && strategy.hasResting()) {
                return filter(strategy.onSessionClose());
            }
            return filter(strategy.onResync());
        }

        List<Action> onTick(long nowMillis) {
            return (phase == Phase.LIVE && sessionOpen && !stopping)
                    ? filter(strategy.onTick(view(), nowMillis)) : List.of();
        }

        List<Action> onStop() {
            return filter(strategy.onSessionClose());
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
                    return List.of();
                }
                case "EXEC", "SESSION" -> {
                    long seq = node.path("seq").asLong(-1);
                    if (seq < 1) {
                        return apply(node);   // a frame without a seq (tests, older servers): apply as is
                    }
                    List<Action> out = new ArrayList<>();
                    for (JsonNode n : seqs.offer(seq, node)) {
                        out.addAll(apply(n));
                    }
                    return out;
                }
                default -> {
                    return List.of();   // FIX echo frames and anything else are ignored
                }
            }
        }

        /** Apply one EXEC or SESSION frame, in seq order. Actions only while live. */
        private List<Action> apply(JsonNode node) {
            if ("EXEC".equals(node.path("type").asText())) {
                ExecReport r = parseExec(node);
                if (isFill(r.execType()) && r.priceUnits() > 0) {
                    lastTradeUnits = r.priceUnits();
                }
                if (gate != null) {
                    gate.onExec(r);
                }
                strategy.onExec(r);
                if (listener != null) {
                    listener.onExec(r);
                }
                return List.of();
            }
            boolean open = "OPEN".equals(node.path("state").asText());
            boolean closing = sessionOpen && !open;
            boolean opening = !sessionOpen && open;
            sessionOpen = open;
            if (phase != Phase.LIVE || stopping) {
                return List.of();   // catching up: the state is folded, the decision waits for LIVE
            }
            if (opening) {
                sessionOpenedLive = true;
            }
            return closing ? filter(strategy.onSessionClose()) : List.of();
        }

        /** True once after a SESSION OPENED was applied while live. */
        boolean takeSessionOpened() {
            boolean v = sessionOpenedLive;
            sessionOpenedLive = false;
            return v;
        }

        long takeRefusals() {
            long n = refusalsPending;
            refusalsPending = 0;
            return n;
        }

        /** Every NEW through the gate; a refused one is never sent and is handed back as a local reject. */
        List<Action> filter(List<Action> actions) {
            if (gate == null || actions.isEmpty()) {
                return actions;
            }
            List<Action> out = new ArrayList<>(actions.size());
            for (Action a : actions) {
                RiskGate.Refusal why = gate.check(a);
                if (why == null) {
                    out.add(a);
                } else {
                    lastRefusal = why;
                    refusalsPending++;
                    strategy.onExec(RiskGate.refusal(a));
                }
            }
            return out;
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

    /** One per connection attempt; its frames and failures are tagged with that attempt's generation. */
    private final class Listener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder textBuf = new StringBuilder();   // socket-reader thread only

        Listener(int gen) {
            this.gen = gen;
        }

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
                post(() -> dispatch(gen, msg));
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            post(() -> onDisconnected(gen, "closed by the venue, status " + statusCode));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            post(() -> onDisconnected(gen, error.toString()));
        }
    }
}
