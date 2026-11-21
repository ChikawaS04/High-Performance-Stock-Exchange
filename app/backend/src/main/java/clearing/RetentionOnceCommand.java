package clearing;

import config.ExchangeConfig;
import db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.ClearingStatus;
import util.DatabaseRetentionStatus;
import util.EpochNanoClock;
import util.ReporterStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One database retention pass against a scratch copy of the venue database, then exit (P16-12, the
 * {@code retention-once} entrypoint mode). It measures the purge on the server before the live database
 * has 15 COMPLETE dates: restore the nightly dump into a scratch database, point {@code DB_URL} at it, set
 * a short {@code DB_RETENTION_DAYS}, run this. The pass logs rows, time and rate per table and the VACUUM
 * time per table, exactly as the venue's own pass does.
 *
 * <p><b>Never the live database.</b> The database named in {@code DB_URL} must end in {@code _bench} or
 * {@code _test}; anything else ({@code exchange}, {@code exchange_dev}, no name) is refused before any
 * connection is made. No Reporter writes a scratch database, so the pass runs with a RUNNING Reporter and
 * an IDLE clearing; every other invariant (COMPLETE dates, the checkpoint in the database, references) is
 * checked as in the venue.
 *
 * <p>Exit status: 0 PURGED or NOTHING_TO_PURGE; 1 SKIPPED, DISABLED or failed; 2 not configured or refused.
 */
public final class RetentionOnceCommand {

    private static final Logger log = LoggerFactory.getLogger(RetentionOnceCommand.class);

    static final int OK = 0;
    static final int FAILED = 1;
    static final int REFUSED = 2;

    /** The database name of a JDBC PostgreSQL URL: after host[:port]/ and before any parameters. */
    private static final Pattern DATABASE = Pattern.compile("^jdbc:postgresql://[^/?#]*/([^/?#;]+)(?:[?#;].*)?$");
    private static final Pattern SCRATCH = Pattern.compile("^[A-Za-z0-9_]+_(bench|test)$");

    private RetentionOnceCommand() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(ExchangeConfig.fromEnvironment());
        } catch (RuntimeException e) {
            log.error("retention-once refused: {}", e.getMessage());
            code = REFUSED;
        }
        System.exit(code);
    }

    /** The database name in {@code url}, or null when the URL names none. */
    static String databaseName(String url) {
        if (url == null) return null;
        Matcher m = DATABASE.matcher(url);
        return m.matches() ? m.group(1) : null;
    }

    /** Null when {@code url} names a scratch database, otherwise why it is refused. */
    static String refusal(String url) {
        if (url == null) return ExchangeConfig.DB_URL + " is not set";
        String name = databaseName(url);
        if (name == null) return ExchangeConfig.DB_URL + " names no database";
        if (!SCRATCH.matcher(name).matches()) {
            return "database " + name + " is not a scratch database (the name must end in _bench or _test);"
                    + " retention-once never runs against the live or development database";
        }
        return null;
    }

    static int run(ExchangeConfig config) {
        String refusal = refusal(config.dbUrl());
        if (refusal != null) {
            log.error("retention-once refused: {}", refusal);
            return REFUSED;
        }
        log.info("retention-once: one pass over database {} with {}={}, batch {} rows, vacuum {}",
                databaseName(config.dbUrl()), ExchangeConfig.DB_RETENTION_DAYS, config.dbRetentionDays(),
                config.dbRetentionBatchRows(), config.dbRetentionVacuum());
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), Database.Timing.RETENTION);
        DatabaseRetention retention = new DatabaseRetention(database::connect, () -> SCRATCH_REPORTER,
                () -> SCRATCH_CLEARING, config.dbRetentionEnabled(), config.dbRetentionDays(),
                config.dbRetentionBatchRows(), config.dbRetentionVacuum(), new EpochNanoClock(),
                DatabaseRetentionTiming.PRODUCTION);
        long t0 = System.nanoTime();
        DatabaseRetentionStatus s = retention.runOnce();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        log.info("retention-once: {} in {} ms; {} rows deleted; purged through {}{}", s.outcome(), ms, s.rowsDeleted(),
                s.purgedThrough(), s.skipReason() == null ? "" : "; " + s.skipReason());
        return DatabaseRetentionStatus.PURGED.equals(s.outcome())
                || DatabaseRetentionStatus.NOTHING_TO_PURGE.equals(s.outcome()) ? OK : FAILED;
    }

    /** No Reporter writes a scratch database; its checkpoint row is what the pass checks. */
    private static final ReporterStatus SCRATCH_REPORTER = new ReporterStatus() {
        @Override public String state() { return "RUNNING"; }
        @Override public long inboundSeq() { return -1L; }
        @Override public long outboundSeq() { return -1L; }
    };

    private static final ClearingStatus SCRATCH_CLEARING = new ClearingStatus() {
        @Override public String state() { return "IDLE"; }
        @Override public int lastClearedDate() { return -1; }
        @Override public int pendingDate() { return -1; }
        @Override public String heldBy() { return null; }
    };
}
