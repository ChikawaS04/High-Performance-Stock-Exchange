package config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure tests of the startup spin warnings (P16-2 carry, D17). */
class SpinBudgetTest {

    private static ExchangeConfig config(String engine, String journal, String threading, String rings) {
        Map<String, String> env = new HashMap<>();
        if (engine != null) env.put(ExchangeConfig.ENGINE_IDLE_STRATEGY, engine);
        if (journal != null) env.put(ExchangeConfig.JOURNAL_IDLE_STRATEGY, journal);
        if (threading != null) env.put(ExchangeConfig.JOURNAL_ARCHIVE_THREADING, threading);
        if (rings != null) env.put(ExchangeConfig.RING_WAIT_STRATEGY, rings);
        return ExchangeConfig.from(env);
    }

    @Test
    void theDefaultsKeepNoThreadHotAndWarnNowhere() {
        ExchangeConfig cfg = ExchangeConfig.from(Map.of());
        assertEquals(0, SpinBudget.hotThreads(cfg));
        assertEquals(List.of(), SpinBudget.warnings(cfg, 1));
        assertEquals(List.of(), SpinBudget.warnings(cfg, 4));
    }

    @Test
    void sleepingAndBackoffAreNeverHot() {
        assertEquals(0, SpinBudget.hotThreads(config("sleeping", "sleeping", "dedicated", "blocking")));
        assertEquals(0, SpinBudget.hotThreads(config("backoff", "backoff", "dedicated", "blocking")));
    }

    @Test
    void theHandoffStartingLayoutFitsFourCores() {
        ExchangeConfig cfg = config("busy-spin", null, null, null);
        assertEquals(1, SpinBudget.hotThreads(cfg));
        assertEquals(List.of(), SpinBudget.warnings(cfg, 4));
    }

    @Test
    void engineAndSharedJournalSpinningFitFourCoresButNotThree() {
        ExchangeConfig cfg = config("busy-spin", "busy-spin", "shared", null);
        assertEquals(3, SpinBudget.hotThreads(cfg));
        assertEquals(List.of(), SpinBudget.warnings(cfg, 4));
        List<String> three = SpinBudget.warnings(cfg, 3);
        assertEquals(1, three.size());
        assertTrue(three.get(0).startsWith("3 threads stay on a core"), three.get(0));
        assertTrue(three.get(0).contains("sees 3 processor(s)"), three.get(0));
    }

    @Test
    void aDedicatedSpinningJournalCountsFourThreads() {
        ExchangeConfig cfg = config("busy-spin", "yielding", "dedicated", null);
        assertEquals(5, SpinBudget.hotThreads(cfg));
        assertEquals(1, SpinBudget.warnings(cfg, 4).size());
        assertEquals(List.of(), SpinBudget.warnings(cfg, 8));
    }

    @Test
    void yieldingCountsAsHot() {
        assertEquals(1, SpinBudget.hotThreads(config("yielding", null, null, null)));
        assertEquals(2, SpinBudget.hotThreads(config(null, "yielding", null, null)));
    }

    @Test
    void theCarriedCombinationAlwaysWarnsEvenOnManyCores() {
        for (String rings : new String[] {"yielding", "busy-spin"}) {
            ExchangeConfig cfg = config("busy-spin", null, null, rings);
            assertEquals(7, SpinBudget.hotThreads(cfg));
            List<String> many = SpinBudget.warnings(cfg, 64);
            assertEquals(1, many.size(), rings);
            assertTrue(many.get(0).contains("RING_WAIT_STRATEGY=" + rings), many.get(0));
            assertEquals(2, SpinBudget.warnings(cfg, 4).size(), "both rules on four cores");
        }
    }

    @Test
    void spinningRingsAloneWarnOnlyByCount() {
        ExchangeConfig cfg = config(null, null, null, "yielding");
        assertEquals(6, SpinBudget.hotThreads(cfg));
        assertEquals(1, SpinBudget.warnings(cfg, 4).size());
        assertEquals(List.of(), SpinBudget.warnings(cfg, 8));
    }

    @Test
    void aSingleProcessorWarnsForAnyHotThread() {
        List<String> w = SpinBudget.warnings(config("busy-spin", null, null, null), 1);
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("at most 0 leave a core"), w.get(0));
    }
}
