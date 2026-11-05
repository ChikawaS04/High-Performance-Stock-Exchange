package session;

import config.ExchangeConfig;
import gateway.OrderGateway;
import journal.JournaledPipeline;
import net.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The venue's startup order from recovery to accepting connections (P15-8), shared by Main and
 * the tests that run a real server, so they exercise the same ordering, as {@link JournaledPipeline}
 * does for the pipeline:
 * <ol>
 *   <li>{@link JournaledPipeline#start()}: consumers, recovery replay on the engine thread, then
 *       the sequencer;</li>
 *   <li>{@link WebSocketServer#open()}: the event loops exist, nothing is bound;</li>
 *   <li>{@link SessionController#startup()} with the worker event loop as the gateway's thread, so
 *       every session command is published by the inbound ring's only producer thread (D15), and
 *       waits until the engine has applied them;</li>
 *   <li>a wait until what those commands produced has been recorded, then a liveness check: a venue
 *       that failed on them is refused here (P15-9);</li>
 *   <li>{@link WebSocketServer#bind()}: only now can a client connect, so no order can reach the
 *       gateway before the session is open, and {@code /health} reports the session from its first
 *       answer.</li>
 * </ol>
 * On any failure the server is stopped and the pipeline shut down before the exception propagates;
 * Main then exits with status 2.
 */
public final class VenueStartup {

    private static final Logger log = LoggerFactory.getLogger(VenueStartup.class);

    private VenueStartup() {
    }

    /** {@link #start(ExchangeConfig, JournaledPipeline, WebSocketServer, OrderGateway, Clock, ReferenceSource)} from configuration. */
    public static SessionController start(ExchangeConfig config, JournaledPipeline pipeline, WebSocketServer server,
                                          OrderGateway gateway, Clock clock) throws InterruptedException {
        return start(config, pipeline, server, gateway, clock, new ConfigReferenceSource(config));
    }

    /**
     * Runs the startup order above.
     *
     * @param gateway the gateway the server feeds, on the pipeline's inbound ring
     * @param clock   the wall clock that decides today's business date
     * @param source  where reference data and the calendar come from
     * @return the session controller, bound to the gateway's thread
     */
    public static SessionController start(ExchangeConfig config, JournaledPipeline pipeline, WebSocketServer server,
                                          OrderGateway gateway, Clock clock, ReferenceSource source)
            throws InterruptedException {
        boolean started = false;
        try {
            pipeline.start();
            server.open();
            SessionController controller = SessionController.fromConfig(config, source, clock, gateway,
                    server.gatewayExecutor(), pipeline);
            controller.startup();
            awaitRecorded(pipeline);
            if (!pipeline.running()) {
                throw new SessionStartupException("The venue stopped (fail-stop) during startup; see the FATAL error"
                        + " above. Not accepting connections.");
            }
            server.bind();
            started = true;
            return controller;
        } finally {
            if (!started) {
                try {
                    server.stop();          // event loops, and the resend service the server owns
                } finally {
                    pipeline.shutdown();    // a no-op if recovery already aborted the pipeline
                }
            }
        }
    }

    /**
     * The controller returns once the engine's session state changes, which is before the session
     * event reaches the recorder, so a recorder failing on that event could still be ahead. Waits,
     * bounded, until every journaled command is applied and every event it produced is recorded, or
     * the venue stops, so the liveness check that follows sees the outcome.
     */
    private static void awaitRecorded(JournaledPipeline pipeline) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SessionController.APPLY_TIMEOUT_MS);
        while (pipeline.running()
                && (pipeline.appliedSeq() < pipeline.journaledSeq() || pipeline.recordedSeq() < pipeline.publishedSeq())) {
            if (System.nanoTime() - deadline > 0) {
                log.warn("The session events were not recorded within {} ms (applied {}, journaled {}, recorded {},"
                        + " published {}); continuing", SessionController.APPLY_TIMEOUT_MS, pipeline.appliedSeq(),
                        pipeline.journaledSeq(), pipeline.recordedSeq(), pipeline.publishedSeq());
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
