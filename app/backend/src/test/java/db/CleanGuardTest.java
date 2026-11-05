package db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clean guard's rule, with no database: exactly {@code exchange_test}, nothing else. */
class CleanGuardTest {

    @Test
    void onlyTheTestDatabaseMayBeCleaned() {
        assertDoesNotThrow(() -> TestDatabase.requireTestDatabase("exchange_test"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"exchange_dev", "EXCHANGE_TEST", "exchange_test2", "exchange_test ", "postgres", ""})
    void anyOtherNameIsRefused(String name) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TestDatabase.requireTestDatabase(name));
        assertTrue(e.getMessage().contains("Refusing to clean"), e.getMessage());
    }
}
