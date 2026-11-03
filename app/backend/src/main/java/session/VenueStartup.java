package session;

import config.ExchangeConfig;
import gateway.OrderGateway;
import journal.JournaledPipeline;
import net.WebSocketServer;

import java.time.Clock;

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
 *   <li>{@link WebSocketServer#bind()}: only now can a client connect, so no order can reach the
 *       gateway before the session is open, and {@code /health} reports the session from its first
 *       answer.</li>
 * </ol>
 * On any failure the server is stopped and the pipeline shut down before the exception propagates;
 * Main then exits with status 2.
 */
public final class VenueStartup {

    private VenueStartup() {
    }

    /**
     * Runs the startup order above.
     *
     * @param gateway the gateway the server feeds, on the pipeline's inbound ring
     * @param clock   the wall clock that decides today's business date
     * @return the session controller, bound to the gateway's thread
     */
    public static SessionController start(ExchangeConfig config, JournaledPipeline pipeline, WebSocketServer server,
                                          OrderGateway gateway, Clock clock) throws InterruptedException {
        boolean started = false;
        try {
            pipeline.start();
            server.open();
            SessionController controller = SessionController.fromConfig(config, clock, gateway,
                    server.gatewayExecutor(), pipeline);
            controller.startup();
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
}
