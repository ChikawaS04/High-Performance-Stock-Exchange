package util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the venue does when a component that must never skip a command cannot go on: the
 * sequencer on a fatal journal offer, the engine agent on any unexpected exception.
 *
 * The caller stops its own thread without advancing past the failing command whether or not
 * {@link #fatal} returns, so a test implementation may record the failure and return. The
 * production instance {@link #HALT} never returns.
 */
@FunctionalInterface
public interface FailStop {

    /** Exit status of a fail-stop halt: sysexits EX_SOFTWARE, distinct from the JVM's generic 1. */
    int EXIT_CODE = 70;

    /**
     * Logs the failure at ERROR and halts the JVM with {@link #EXIT_CODE}. Shutdown hooks are
     * skipped on purpose: a graceful drain cannot complete on a broken pipeline, and the
     * journal already holds every command, so the restart recovers from it (P15-5).
     * slf4j-simple writes synchronously, so the log line is out before the halt.
     */
    FailStop HALT = (component, cause) -> {
        Logger log = LoggerFactory.getLogger(FailStop.class);
        log.error("FATAL in {}: venue halted with exit code {}; the journal holds every command"
                + " sequenced so far and none was skipped", component, EXIT_CODE, cause);
        Runtime.getRuntime().halt(EXIT_CODE);
    };

    /** Reports that {@code component} has stopped on {@code cause}. */
    void fatal(String component, Throwable cause);
}
