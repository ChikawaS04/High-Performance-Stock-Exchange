package integration;

import gateway.OrderGateway;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * The process {@link HardKillRecoveryTest} kills: builds the venue on the directory given as the
 * only argument, applies the recovery scenario through the gateway, prints {@code READY <seq>}
 * and then waits to be killed. It never shuts down, so no snapshot is written and the journal's
 * recording is left active, exactly as after a crash.
 */
public final class CrashChild {

    private CrashChild() { }

    public static void main(String[] args) {
        Path root = Path.of(args[0]);
        List<byte[]> frames = RecoveryScenario.frames();
        JournaledVenue venue = JournaledVenue.create(root);
        OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
        venue.start();
        for (byte[] frame : frames) gateway.onFrame(frame);
        RecoveryScenario.await(() -> venue.pipeline().appliedSeq() == frames.size(), "scenario applied");
        System.out.println("READY " + venue.pipeline().appliedSeq());
        System.out.flush();
        while (true) {
            LockSupport.parkNanos(1_000_000_000L);
        }
    }
}
