package integration;

import event.CapturingExecutionHandler;
import gateway.OrderGateway;
import journal.Journal;
import journal.OutboundRecordings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import recovery.EngineStates;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real hard kill (P15-5, SRS §3.10): a child JVM runs the venue, applies the recovery scenario
 * and is killed with no shutdown at all, so no snapshot is written, the driver's heartbeat is
 * left fresh and the inbound recording is left active. The venue is then restarted at once on the
 * same data and driver directories, as an operator or a supervisor would. The first launch is
 * refused by the fresh heartbeat, the one retry succeeds after it ages out, the Archive repairs the
 * active recording (with the per-frame checksum), and recovery replays the whole journal to the
 * same state as a reference run of the same input.
 *
 * Takes about 15 s: the driver's liveness window is 10 s and cannot be shortened.
 */
class HardKillRecoveryTest {

    @TempDir
    Path root;

    @TempDir
    Path reference;

    @Test
    void aKilledVenueRestartsOnTheSameDataWithItsBookIntact() throws Exception {
        List<String> expected = referenceState();

        killChild();

        long start = System.nanoTime();
        JournaledVenue recovered = JournaledVenue.create(root);   // refused once, retried after 11 s
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(-1, recovered.pipeline().recoveredSnapshotSeq(), "a killed venue wrote no snapshot");

        OrderGateway gateway = recovered.gateway(RecoveryScenario.countingClock(4_000_000_000_000_000_000L));
        recovered.start();   // replays on the engine thread; no command sent yet, so the book is safe to read
        assertEquals(RecoveryScenario.commands(), recovered.pipeline().replayedCommands());
        assertEquals(expected, EngineStates.describe(recovered.engine()), "the book came back");
        gateway.onFrame(FixMessages.cancel(900, 116));
        RecoveryScenario.await(() -> recovered.pipeline().appliedSeq() == RecoveryScenario.commands() + 1,
                "a command after recovery");
        recovered.close();
        assertTrue(waitedMs >= 10_000L, "the first launch met the dead run's fresh heartbeat and retried, waited "
                + waitedMs + " ms");
    }

    /**
     * P15-6: the child's recorder stalls before seq {@value #HOLD_AT}, so events from there on are
     * broadcast but not recorded when the process is killed with its outbound recording active.
     * Recovery publishes exactly those events once, and the recording ends up identical to a
     * reference run's: every seq from 1, no gap and no duplicate.
     */
    @Test
    void eventsBroadcastButUnrecordedAtAKillAreRecordedOnceOnRestart() throws Exception {
        List<CapturingExecutionHandler.Observed> reference = referenceRecording();

        List<String> output = killChild(Long.toString(HOLD_AT));
        assertTrue(output.contains("PUBLISHED " + reference.size()), String.join("\n", output));
        List<CapturingExecutionHandler.Observed> beforeRestart = recording(root);   // retries past the heartbeat
        assertEquals(reference.subList(0, (int) HOLD_AT - 1), beforeRestart, "the kill left 1 to K-1 recorded");

        CapturingExecutionHandler published = new CapturingExecutionHandler();
        JournaledVenue recovered = JournaledVenue.create(root);
        recovered.outbound().handleEventsWith(published);
        recovered.start();
        assertEquals(HOLD_AT - 1, recovered.pipeline().outboundFloor());
        assertEquals(reference.subList((int) HOLD_AT - 1, reference.size()), published.snapshot(),
                "exactly the unrecorded events, once, identical to the reference run's");
        recovered.close();

        List<CapturingExecutionHandler.Observed> after = recording(root);
        OutboundRecordings.assertGapFreeFromOne(after);
        assertEquals(reference, after, "no gap and no duplicate after the restart");
    }

    private static final long HOLD_AT = 6;

    /** Starts the child with {@code extraArgs}, waits for READY, kills it hard; returns its output. */
    private List<String> killChild(String... extraArgs) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                CrashChild.class.getName(), root.toString()));
        command.addAll(List.of(extraArgs));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) output.add(line);
            } catch (IOException ignored) {
                // the stream closes when the child dies
            }
        }, "crash-child-output");
        reader.setDaemon(true);
        reader.start();
        try {
            RecoveryScenario.await(() -> output.stream().anyMatch(l -> l.startsWith("READY "))
                    || !child.isAlive(), "the child to apply the scenario");
            assertTrue(output.contains("READY " + RecoveryScenario.commands()), String.join("\n", output));
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child died");
        }
        return new ArrayList<>(output);
    }

    /** The outbound recording under {@code dir}, launching the journal with its one crash retry. */
    private static List<CapturingExecutionHandler.Observed> recording(Path dir) {
        try (Journal journal = Journal.launchRetryingOnce(JournaledVenue.config(dir), Journal.CRASH_RETRY_DELAY_MS)) {
            return OutboundRecordings.readAll(journal);
        }
    }

    private List<CapturingExecutionHandler.Observed> referenceRecording() {
        referenceState();
        return recording(reference);
    }

    private List<String> referenceState() {
        try (JournaledVenue venue = JournaledVenue.create(reference)) {
            OrderGateway gateway = venue.gateway(RecoveryScenario.countingClock(1_000L));
            venue.startAndOpen(gateway);
            for (byte[] frame : RecoveryScenario.frames()) gateway.onFrame(frame);
            RecoveryScenario.await(() -> venue.pipeline().appliedSeq() == RecoveryScenario.commands(),
                    "reference applied");
            venue.close();
            return EngineStates.describe(venue.engine());
        }
    }
}
