package db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings the schema to the migrations in the repository (SRS §3.13). Clean is disabled here in code
 * and is not configurable: only the test helper can enable it, and only against {@code exchange_test}.
 */
public final class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);

    public static final String LOCATION = "classpath:db/migration";

    private final Flyway flyway;
    private final boolean migrate;

    /** The repository's migrations. */
    public Migrator(Database database, boolean migrate) {
        this(database, migrate, LOCATION);
    }

    /**
     * @param migrate  true applies pending migrations; false only validates, so pending or changed
     *                 migrations fail (migrations ran earlier, as a pre-deploy command)
     * @param location where the migrations are
     */
    Migrator(Database database, boolean migrate, String location) {
        this.flyway = Flyway.configure()
                .dataSource(database.dataSource())
                .locations(location)
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .load();
        this.migrate = migrate;
    }

    /**
     * The schema step: migrate or validate.
     *
     * @throws org.flywaydb.core.api.FlywayException if it fails; {@link Database#isUnavailable} tells why
     */
    public void run() {
        if (migrate) {
            MigrateResult result = flyway.migrate();
            // Flyway leaves the target version null when nothing was pending.
            String version = result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
            log.info("Database schema at version {}: {} migration(s) applied", version, result.migrationsExecuted);
        } else {
            flyway.validate();
            log.info("Database schema validated against the repository's migrations");
        }
    }

    /** For tests: the configured Flyway. */
    Flyway flyway() {
        return flyway;
    }
}
