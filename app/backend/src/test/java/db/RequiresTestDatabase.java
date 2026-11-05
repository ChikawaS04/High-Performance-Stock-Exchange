package db;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class or method that needs {@code exchange_test} (D17). With {@code TEST_DB_URL}
 * unset it is skipped, and {@link TestDatabaseCondition} reports the reason.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("database")
@ExtendWith(TestDatabaseCondition.class)
public @interface RequiresTestDatabase {
}
