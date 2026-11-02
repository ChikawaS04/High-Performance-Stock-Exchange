package integration;

import config.ExchangeConfig;
import gateway.OrderGateway;
import journal.JournaledPipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * The process {@link HardKillRecoveryTest} kills: builds the venue on the directory given as the
 * first argument, applies the recovery scenario through the gateway, prints {@code READY <seq>}
 * and then waits to be killed. It never shuts down, so no snapshot is written and the journal's
 * recordings are left active, exactly as after a crash.
 *
 * With a second argument {@code K} (P15-6), the outbound recorder blocks before recording seq K,
 * as a stalled recorder would: every event from K on is published but never recorded when the
 * process dies. {@code READY} then waits until every event is published.
 */
public final class CrashChild {

    private CrashChild() { }

    public static void main(String[] args) {
        Path root = Path.of(args[0]);
        long holdAt = args.length > 1 ? Long.parseLong(args[1]) : -1;
        LongConsumer beforeRecord = holdAt < 0 ? null : seq -> {
            while (seq >= holdAt) {
                LockSupport.parkNanos(1_000_000_000L);   // the recorder never gets past holdAt
            }
        };
        List<byte[]> frames = RecoveryScenario.frames();
        ExchangeConfig config = JournaledVenue.config(root);
        JournaledVenue venue = JournaledVenue.create(config, new JournaledPipeline.Seams(null, null, beforeRecord));
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
        venue.start();
        for (byte[] frame : frames) gateway.onFrame(frame);
        RecoveryScenario.await(() -> venue.pipeline().appliedSeq() == frames.size(), "scenario applied");
        if (holdAt > 0) {
            RecoveryScenario.await(() -> venue.pipeline().recordedSeq() == holdAt - 1, "recorder held at " + holdAt);
            System.out.println("PUBLISHED " + venue.pipeline().publishedSeq());
        }
        System.out.println("READY " + venue.pipeline().appliedSeq());
        System.out.flush();
        while (true) {
            LockSupport.parkNanos(1_000_000_000L);
        }
    }
}
