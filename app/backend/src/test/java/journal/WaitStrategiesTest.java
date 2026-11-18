package journal;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import config.ExchangeConfig;
import io.aeron.archive.Archive;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure tests of the P16-2 mappings: each wait and idle setting to its strategy, and the journal
 * idle setting into the media driver and Archive contexts. Nothing is launched.
 */
class WaitStrategiesTest {

    @TempDir
    Path root;

    @Test
    void everyEngineIdleSettingMapsToItsStrategy() {
        assertInstanceOf(BackoffIdleStrategy.class, WaitStrategies.engineIdle(ExchangeConfig.EngineIdle.BACKOFF));
        assertInstanceOf(SleepingMillisIdleStrategy.class, WaitStrategies.engineIdle(ExchangeConfig.EngineIdle.SLEEPING));
        assertInstanceOf(YieldingIdleStrategy.class, WaitStrategies.engineIdle(ExchangeConfig.EngineIdle.YIELDING));
        assertInstanceOf(BusySpinIdleStrategy.class, WaitStrategies.engineIdle(ExchangeConfig.EngineIdle.BUSY_SPIN));
    }

    @Test
    void journalBackoffSetsNothingSoAeronKeepsItsDefault() {
        assertNull(WaitStrategies.journalIdle(ExchangeConfig.EngineIdle.BACKOFF));
    }

    @Test
    void everyOtherJournalIdleSettingSuppliesItsStrategy() {
        assertInstanceOf(SleepingMillisIdleStrategy.class,
                WaitStrategies.journalIdle(ExchangeConfig.EngineIdle.SLEEPING).get());
        assertInstanceOf(YieldingIdleStrategy.class,
                WaitStrategies.journalIdle(ExchangeConfig.EngineIdle.YIELDING).get());
        assertInstanceOf(BusySpinIdleStrategy.class,
                WaitStrategies.journalIdle(ExchangeConfig.EngineIdle.BUSY_SPIN).get());
    }

    @Test
    void theJournalSupplierGivesEachThreadItsOwnInstance() {
        for (ExchangeConfig.EngineIdle idle : ExchangeConfig.EngineIdle.values()) {
            if (idle == ExchangeConfig.EngineIdle.BACKOFF) continue;
            Supplier<IdleStrategy> supplier = WaitStrategies.journalIdle(idle);
            assertNotSame(supplier.get(), supplier.get(), idle.value());
        }
    }

    @Test
    void everyRingWaitSettingMapsToItsStrategy() {
        assertInstanceOf(BlockingWaitStrategy.class, WaitStrategies.ringWait(ExchangeConfig.RingWait.BLOCKING));
        assertInstanceOf(YieldingWaitStrategy.class, WaitStrategies.ringWait(ExchangeConfig.RingWait.YIELDING));
        assertInstanceOf(BusySpinWaitStrategy.class, WaitStrategies.ringWait(ExchangeConfig.RingWait.BUSY_SPIN));
        assertNotSame(WaitStrategies.ringWait(ExchangeConfig.RingWait.BLOCKING),
                WaitStrategies.ringWait(ExchangeConfig.RingWait.BLOCKING), "one instance per ring");
    }

    @Test
    void theDefaultLeavesTheDriverAndArchiveIdleUnset() {
        ExchangeConfig config = config(Map.of());
        assertNull(Journal.driverContext(config).sharedIdleStrategy(), "Aeron fills in its default at conclude");
        // Archive.Context has no getter for an unset supplier; its toString names each one.
        String archive = Journal.archiveContext(config).toString();
        assertTrue(archive.contains("idleStrategySupplier=null"), archive);
        assertTrue(archive.contains("recorderIdleStrategySupplier=null"), archive);
        assertTrue(archive.contains("replayerIdleStrategySupplier=null"), archive);
        assertEquals("backoff (Aeron default)", Journal.journalIdleDescription(config));
    }

    @Test
    void aJournalIdleSettingReachesTheDriverAndEveryArchiveThread() {
        Map<ExchangeConfig.EngineIdle, Class<? extends IdleStrategy>> expected = Map.of(
                ExchangeConfig.EngineIdle.SLEEPING, SleepingMillisIdleStrategy.class,
                ExchangeConfig.EngineIdle.YIELDING, YieldingIdleStrategy.class,
                ExchangeConfig.EngineIdle.BUSY_SPIN, BusySpinIdleStrategy.class);
        for (Map.Entry<ExchangeConfig.EngineIdle, Class<? extends IdleStrategy>> entry : expected.entrySet()) {
            for (ExchangeConfig.ArchiveThreading threading : ExchangeConfig.ArchiveThreading.values()) {
                ExchangeConfig config = config(Map.of(
                        ExchangeConfig.JOURNAL_IDLE_STRATEGY, entry.getKey().value(),
                        ExchangeConfig.JOURNAL_ARCHIVE_THREADING, threading.value()));
                String label = entry.getKey().value() + "/" + threading.value();
                MediaDriver.Context driver = Journal.driverContext(config);
                Archive.Context archive = Journal.archiveContext(config);
                assertInstanceOf(entry.getValue(), driver.sharedIdleStrategy(), label);
                assertInstanceOf(entry.getValue(), archive.idleStrategy(), label);
                assertInstanceOf(entry.getValue(), archive.recorderIdleStrategy(), label);
                assertInstanceOf(entry.getValue(), archive.replayerIdleStrategy(), label);
                assertEquals(entry.getKey().value(), Journal.journalIdleDescription(config));
            }
        }
    }

    private ExchangeConfig config(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(extra);
        env.put(ExchangeConfig.EXCHANGE_DATA_DIR, root.resolve("data").toString());
        env.put(ExchangeConfig.AERON_DIR, root.resolve("aeron").toString());
        return ExchangeConfig.from(env);
    }
}
