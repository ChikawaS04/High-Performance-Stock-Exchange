package db;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skips database tests when {@code TEST_DB_URL} is unset, never silently (D17): the reason goes into
 * the Surefire reports with each skipped test, and once onto the build's output.
 */
public final class TestDatabaseCondition implements ExecutionCondition {

    static final String SKIP_REASON = TestDatabase.TEST_DB_URL + " is unset: database tests skipped";

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (TestDatabase.configured()) {
            return ConditionEvaluationResult.enabled(TestDatabase.TEST_DB_URL + " is set");
        }
        if (ANNOUNCED.compareAndSet(false, true)) {
            System.err.println("[WARNING] " + SKIP_REASON + " (see the Skipped count in the summary)");
        }
        return ConditionEvaluationResult.disabled(SKIP_REASON);
    }
}
