import config.ExchangeConfig;
import engine.MatchingEngine;
import gateway.OrderGateway;
import journal.JournaledPipeline;
import market.MarketDataService;
import net.ResendService;
import net.WebSocketServer;
import publisher.ExecutionFrames;
import publisher.TradeLogger;
import publisher.WebSocketPublisher;
import session.VenueStartup;
import util.EpochNanoClock;
import util.FailStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * Assembles the venue and runs it until a termination signal.
 *
 * <p>Note: no package declaration. Main sits at the root of {@code src/main/java} per the
 * SRS §7 project structure.
 *
 * <p>The wired path, end to end (P15-4, SRS §1.2):
 * <pre>
 *   React client --ws--> WebSocketFrameHandler --JSON--> JsonToFix --FIX bytes-->
 *   OrderGateway --> [inbound ring] --> Sequencer --> recorded inbound stream (Aeron IPC + Archive)
 *        --> EngineAgent (gated on the recorded position) --> MatchingEngineHandler --> MatchingEngine
 *                                              |
 *                                              +--> [outbound ring]  --> OutboundRecorder --> recorded outbound stream
 *                                              |                     --> WebSocketPublisher (EXEC)
 *                                              |                     --> TradeLogger
 *                                              +--> [snapshot ring]  --> WebSocketPublisher (BOOK)
 *                                                                    --> MarketDataService
 * </pre>
 * No command is matched before the Archive has recorded it (SRS §5.7). Every execution event is
 * recorded on the outbound stream (P15-6), which RESEND replays to the requesting client only.
 *
 * <p><b>Configuration.</b> Every setting comes from environment variables with development
 * defaults ({@link ExchangeConfig}, D9). The JVM needs
 * {@code --add-exports java.base/jdk.internal.misc=ALL-UNNAMED} for Agrona; from
 * {@code app/backend} run {@code mvn -q compile exec:exec@exchange}, or the shared IntelliJ run
 * configuration "Exchange Main".
 *
 * <p><b>Single-writer discipline (§5.2).</b> Each ring and stream has one producer: the gateway
 * on inbound (called only from the single Netty worker), the sequencer on the recorded inbound
 * stream, and {@code MatchingEngineHandler} on both outbound rings (on the engine agent's
 * thread). This class never touches the book: every order enters through the gateway.
 *
 * <p><b>Startup order</b> ({@link JournaledPipeline} documents its part): journal, outbound
 * recording (its last seq is the replay publish floor), newest valid snapshot, inbound
 * publication and sequencer, engine subscription, rings, recorder and agent; then the gateway,
 * resend service, server and publisher, with subscribers registered; then consumers start, the
 * engine thread replays the journal tail (publishing and recording only the events the outbound
 * recording lacks) and the recovered book; then the server's event loops are created, the session
 * controller issues the session commands on the worker event loop (the gateway's only thread, D15)
 * and waits until the engine has applied them, and only then is the port bound
 * ({@link VenueStartup}, P15-8). So the engine is subscribed before the first command is offered,
 * and no client order can arrive before the session is open. A restart within 10 s of a crash
 * waits and retries the journal once. A snapshot that disagrees with the journal, data from
 * before the session lifecycle, or a session the controller cannot open refuses startup (exit 2)
 * with a message saying what to do.
 *
 * <p><b>Shutdown.</b> On a termination signal: stop the server (no more input), then drain the
 * pipeline by progress, write a snapshot of the final state and close the journal, so the next
 * start replays nothing. Shutdown does not close the session: a restart is not the end of the
 * trading day, and the next start resumes it. A fatal error in the sequencer or engine halts the JVM with
 * {@link FailStop#EXIT_CODE} instead, skipping this hook; the next start recovers from the
 * newest snapshot and the journal.
 *
 * <p><b>Time (P7-1, extended P7-2).</b> One {@link EpochNanoClock} is constructed here and shared
 * by the gateway (receipt stamp, which the journal keeps, D1) and the WebSocket edge (raw-FIX echo
 * stamp), so both live in one epoch-nanos domain. The engine reads no clock (P15-2).
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** Exit status when the configuration, the journal or recovery refuses startup. */
    private static final int STARTUP_FAILED = 2;

    private Main() {
        // Entry point only.
    }

    public static void main(String[] args) throws Exception {
        // --- 1. Configuration --------------------------------------------------------------
        final ExchangeConfig config;
        final JournaledPipeline pipeline;
        try {
            config = ExchangeConfig.fromEnvironment();
            log.info("Starting with {}", config);

            // --- 2. Journal, recordings, snapshot, sequencer, engine subscription, rings, agent --
            pipeline = new JournaledPipeline(config, new MatchingEngine(), FailStop.HALT);
        } catch (RuntimeException e) {
            log.error("Startup refused: {}", e.getMessage(), e);
            System.exit(STARTUP_FAILED);
            return;
        }

        // --- 3. Gateway and network edge ---------------------------------------------------
        // One epoch-nanos clock, shared by the gateway and the WebSocket edge (P7-1).
        EpochNanoClock clock = new EpochNanoClock();
        OrderGateway gateway = new OrderGateway(pipeline.inboundRing(), clock);
        // RESEND: replays the outbound recording with the live EXEC serializer; the server owns it.
        ResendService resend = new ResendService(pipeline.outboundHistory(), new ExecutionFrames()::toJson);
        // Constructed before the publisher: the server owns the ChannelGroup and the BOOK cache.
        // The pipeline is what GET /health reports (P15-7).
        WebSocketServer server = new WebSocketServer(config.port(), gateway, clock, resend, pipeline);
        WebSocketPublisher publisher = new WebSocketPublisher(server.getChannelGroup(), server.getBookFrameCache());

        // --- 4. Subscribers (must precede start). Independent consumers, each with its own
        // sequence (§3.4); an exception in one is logged and never stalls the engine. ------
        pipeline.outbound().handleEventsWith(publisher.executionHandler(), new TradeLogger());
        pipeline.snapshots().handleEventsWith(publisher.snapshotHandler(), new MarketDataService());

        // --- 5. Recover, open the session on the gateway's thread, then accept connections ---
        try {
            VenueStartup.start(config, pipeline, server, gateway, Clock.systemUTC());
        } catch (RuntimeException e) {
            // VenueStartup has stopped the server and shut the pipeline down.
            log.error("Startup refused: {}", e.getMessage(), e);
            System.exit(STARTUP_FAILED);
            return;
        }

        log.info("Exchange up. WebSocket endpoint: ws://localhost:{}/ws, health: http://localhost:{}/health",
                server.boundPort(), server.boundPort());
        log.info("Submit an order:  {}", "{\"type\":\"NEW\",\"clOrdId\":1,\"side\":\"BUY\","
                + "\"price\":1500000,\"qty\":10,\"symbol\":\"ASML\"}");
        log.info("Cancel an order:  {}", "{\"type\":\"CANCEL\",\"clOrdId\":2,\"origClOrdId\":1}");
        log.info("Resend history:   {}", "{\"type\":\"RESEND\",\"fromSeq\":1}");
        log.info("Ctrl+C to stop.");

        awaitShutdown(server, pipeline);
    }

    /**
     * Parks the main thread until the JVM is asked to exit, then stops input and drains the
     * pipeline (bounded) before closing the journal.
     */
    private static void awaitShutdown(WebSocketServer server, JournaledPipeline pipeline) throws InterruptedException {
        CountDownLatch shutdown = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            boolean clean = false;
            try {
                server.stop();                // no new frames can enter the gateway; resend stopped
                clean = pipeline.shutdown();  // journal and apply everything, snapshot, drain, close
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            } finally {
                shutdown.countDown();
            }
            if (clean) {
                log.info("Shutdown complete.");
            } else {
                log.error("Shutdown incomplete: see the errors above.");
            }
        }, "shutdown-hook"));

        shutdown.await();
    }
}
