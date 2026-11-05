package db;

import config.ExchangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies pending migrations and exits: the pre-deploy command, run before a venue started with
 * {@code DB_MIGRATE_ON_START=false}. From {@code app/backend}: {@code mvn -q compile exec:exec@migrate}.
 */
public final class MigrateCommand {

    private static final Logger log = LoggerFactory.getLogger(MigrateCommand.class);

    static final int OK = 0;
    static final int FAILED = 1;
    static final int NOT_CONFIGURED = 2;

    private MigrateCommand() {
    }

    public static void main(String[] args) {
        System.exit(run(ExchangeConfig.fromEnvironment()));
    }

    static int run(ExchangeConfig config) {
        if (config.dbUrl() == null) {
            log.error("{} is not set: nothing to migrate", ExchangeConfig.DB_URL);
            return NOT_CONFIGURED;
        }
        try {
            new Migrator(Database.from(config), true).run();
            return OK;
        } catch (RuntimeException e) {
            log.error("Migration failed: {}", e.getMessage(), e);
            return FAILED;
        }
    }
}
