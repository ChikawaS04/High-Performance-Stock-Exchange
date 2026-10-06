import engine.MatchingEngine;
import engine.MatchingEngineHandler;
import event.InboundPipeline;
import event.OutboundPipeline;
import event.SnapshotPipeline;
import gateway.OrderGateway;
import market.MarketDataService;
import net.WebSocketServer;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import util.EpochNanoClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * P4-7 bring-up. Assembles the full pipeline and runs the demo server.
 *
 * <p>Note: no package declaration — Main sits at the root of {@code src/main/java} per the
 * SRS §7 project structure.
 *
 * <p>The wired path, end to end:
 * <pre>
 *   React client --ws--> WebSocketFrameHandler --JSON--> JsonToFix --FIX bytes-->
 *   OrderGateway --> [inbound ring] --> MatchingEngineHandler --> MatchingEngine
 *        |                                     |
 *        |                                     +--> [outbound ring]  --> WebSocketPublisher (EXEC)
 *        |                                     |                     --> TradeLogger
 *        |                                     +--> [snapshot ring]  --> WebSocketPublisher (BOOK)
 *        |                                                           --> MarketDataService
 * </pre>
 *
 * <p><b>Single-writer discipline (§5.2).</b> Three rings, each with exactly one producer:
 * the gateway on inbound (called only from the Netty worker, which is one thread —
 * guide decision 2), and {@code MatchingEngineHandler} on both outbound rings (same object,
 * same inbound-consumer thread). This class never touches the book directly: every order
 * enters through the gateway, so the engine is mutated only on the inbound consumer thread.
 * That is also why there is no book seeding here — seeding would mean publishing from the
 * main thread. Let a client send the first order.
 *
 * <p><b>Startup order matters.</b> Outbound and snapshot consumers must be running before
 * inbound starts, or the engine publishes into rings nobody is draining. Shutdown is the
 * strict reverse: stop accepting input first, then drain inward-out.
 *
 * <p><b>Time (P7-1, extended P7-2).</b> One {@link EpochNanoClock} is constructed here and shared
 * by every timestamp site, so all observable stamps (EXEC, BOOK), the gateway's receipt stamp
 * (Order.timeStamp) and the raw-FIX echo stamp live in one epoch-nanos domain. The clock is
 * affine over System.nanoTime(), so inter-event deltas stay exact and the §6.3 latency benchmark
 * is unaffected. Sharing ONE instance is load-bearing: two anchors would differ by the anchor
 * skew, corrupting a receipt->publish delta.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** Demo default; override with the first CLI arg. */
    private static final int DEFAULT_PORT = 8080;

    private Main() {
        // Entry point only.
    }

    public static void main(String[] args) throws Exception {
        final int port = parsePort(args);

        // --- 1. Engine ------------------------------------------------------------------
        MatchingEngine engine = new MatchingEngine();

        // --- 2. Clock -------------------------------------------------------------------
        // One epoch-nanos clock, shared by the gateway (receipt stamp), the engine handler
        // (EXEC + BOOK stamps) and the WebSocket edge (P7-2 raw FIX echo stamp), so the whole
        // system stamps in a single domain. Anchored once, here, at startup. Must be the SAME
        // instance at every site (P7-1).
        EpochNanoClock clock = new EpochNanoClock();

        // --- 3. Outbound rings (constructed; consumers registered below, started later) ---
        OutboundPipeline outbound = new OutboundPipeline();
        SnapshotPipeline snapshots = new SnapshotPipeline();

        // --- 4. Engine handler: sole producer of BOTH outbound rings --------------------
        MatchingEngineHandler engineHandler = new MatchingEngineHandler(
                engine,
                outbound.getRingBuffer(),
                snapshots.getRingBuffer(),
                clock);

        // Without this the engine reports nothing: fills are surfaced through the
        // ExecutionListener seam, which defaults to NO_OP.
        engine.setExecutionListener(engineHandler);

        // --- 5. Inbound ring (attaches its consumer at construction) --------------------
        InboundPipeline inbound = new InboundPipeline(engineHandler);
        OrderGateway gateway = new OrderGateway(inbound.getRingBuffer(), clock);

        // --- 6. Network edge ------------------------------------------------------------
        // Constructed before the publisher: the server owns the ChannelGroup, which exists
        // at construction time (pre-start) and is the single source of truth for clients.
        WebSocketServer server = new WebSocketServer(port, gateway, clock);
        WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup(), server.getBookFrameCache());

        // --- 7. Register outbound subscribers (must precede start) ----------------------
        // Independent consumers, each with its own sequence counter (§3.4) — none blocks
        // the others or the engine.
        TradeLogger tradeLogger = new TradeLogger();
        MarketDataService marketData = new MarketDataService();

        outbound.handleEventsWith(publisher.executionHandler(), tradeLogger);
        snapshots.handleEventsWith(publisher.snapshotHandler(), marketData);

        // --- 8. Start: outbound first, then inbound, then accept connections ------------
        outbound.start();
        snapshots.start();
        inbound.start();
        server.start();

        log.info("OMS up. WebSocket endpoint: ws://localhost:{}/ws", server.boundPort());
        log.info("Submit an order:  {}", "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\","
                + "\"price\":15000,\"qty\":10,\"symbol\":\"ASML\"}");
        log.info("Cancel an order:  {}", "{\"type\":\"CANCEL\",\"clOrdId\":2,\"origClOrdId\":1}");
        log.info("Ctrl+C to stop.");

        awaitShutdown(server, inbound, snapshots, outbound);
    }

    /**
     * Parks the main thread until the JVM is asked to exit, then tears the pipeline down in
     * the strict reverse of startup: stop accepting input, then drain each ring inward-out.
     */
    private static void awaitShutdown(WebSocketServer server,
                                      InboundPipeline inbound,
                                      SnapshotPipeline snapshots,
                                      OutboundPipeline outbound) throws InterruptedException {
        CountDownLatch shutdown = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                server.stop();        // no new frames can enter the gateway
                inbound.shutdown();   // drain queued orders through the engine
                snapshots.shutdown(); // then the two outbound streams the engine fed
                outbound.shutdown();
            } catch (Exception e) {
                log.warn("Error during shutdown", e);
            } finally {
                shutdown.countDown();
            }
            log.info("Shutdown complete.");
        }, "shutdown-hook"));

        shutdown.await();
    }

    private static int parsePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            log.warn("Unparseable port '{}', using default {}", args[0], DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}