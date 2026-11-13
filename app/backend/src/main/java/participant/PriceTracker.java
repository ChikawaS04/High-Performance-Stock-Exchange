package participant;

import model.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Read-only observer that records how price moves and which bot moved it. It is wired as the
 * {@link ParticipantClient.MarketListener} of a non-trading observer connection, so it sees the
 * same broadcast BOOK and EXEC streams the terminal does, and writes one CSV row per BOOK update
 * and per trade print, tagging each trade's aggressor side and the owner of each side by id block
 * ({@link Bots}). The terminal shows the live path; this file is the after-the-fact record,
 * correlated to the bot behind each move.
 *
 * <p>All callbacks arrive on the observer client's single executor thread, so the writer and the
 * counters need no synchronization.
 */
public final class PriceTracker implements ParticipantClient.MarketListener {

    private static final Logger log = LoggerFactory.getLogger(PriceTracker.class);

    private final BufferedWriter out;

    private long bestBid = Prices.NA;
    private long bestAsk = Prices.NA;
    private long lastTrade = Prices.NA;

    private final Map<Bots.Owner, Long> aggressedBy = new EnumMap<>(Bots.Owner.class);
    private long trades;

    public PriceTracker(Path csv) {
        try {
            Path parent = csv.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8);
            out.write("eventTimestampNanos,wallClockMillis,event,bestBid,bestAsk,mid,lastTrade,"
                    + "tradePrice,tradeQty,aggressorSide,aggressor,passive,aggressorId,passiveId");
            out.newLine();
            log.info("PriceTracker writing {}", csv.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open price-path CSV", e);
        }
    }

    @Override
    public void onBook(long newBid, long newAsk, long mid, long timestamp) {
        this.bestBid = newBid;
        this.bestAsk = newAsk;
        row(timestamp, "BOOK", mid, Prices.NA, Prices.NA, "",
                Bots.Owner.OTHER, Bots.Owner.OTHER, Prices.NA, Prices.NA);
    }

    @Override
    public void onExec(ExecReport e) {
        if (!ParticipantClient.isFill(e.execType())) {
            return;
        }
        this.lastTrade = e.priceUnits();
        long mid = (bestBid > 0 && bestAsk > 0) ? (bestBid + bestAsk) / 2 : Prices.NA;

        Bots.Owner agg = Bots.owner(e.aggressorOrderId());
        Bots.Owner pas = Bots.owner(e.passiveOrderId());
        trades++;
        aggressedBy.merge(agg, 1L, Long::sum);
        String side = e.side() == null ? "" : e.side().name();

        row(e.timestamp(), "FILL", mid, e.priceUnits(), e.filledQuantity(), side,
                agg, pas, e.aggressorOrderId(), e.passiveOrderId());
    }

    private void row(long tsNanos, String event, long mid, long tradePx, long tradeQty, String side,
                     Bots.Owner agg, Bots.Owner pas, long aggId, long pasId) {
        try {
            out.write(tsNanos + "," + System.currentTimeMillis() + "," + event + ","
                    + bestBid + "," + bestAsk + "," + mid + "," + lastTrade + ","
                    + tradePx + "," + tradeQty + "," + side + "," + agg + "," + pas + ","
                    + aggId + "," + pasId);
            out.newLine();
        } catch (IOException e) {
            log.warn("CSV write failed: {}", e.toString());
        }
    }

    /** Flush, summarize, and close. Safe to call once at shutdown. */
    public void close() {
        try {
            out.flush();
            out.close();
        } catch (IOException e) {
            log.warn("CSV close failed: {}", e.toString());
        }
        log.info("Summary: {} trades printed; aggressor by owner {}", trades, aggressedBy);
    }

    // Test accessors.
    long tradesForTest() { return trades; }
    long aggressedByForTest(Bots.Owner owner) { return aggressedBy.getOrDefault(owner, 0L); }
}
