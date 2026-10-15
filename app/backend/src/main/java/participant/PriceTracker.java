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

/**
 * Read-only observer that records how price moves and which bot moved it. It is wired as the
 * {@link ParticipantClient.MarketListener} of a non-trading observer connection, so it sees the
 * same broadcast BOOK and EXEC streams the terminal does, and writes one CSV row per BOOK update
 * and per trade print, tagging each trade's aggressor and passive side by clOrdId range
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

    private long makerAggTrades;
    private long takerAggTrades;
    private long trades;

    public PriceTracker(Path csv) {
        try {
            Files.createDirectories(csv.getParent());
            this.out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8);
            out.write("eventTimestampNanos,wallClockMillis,event,bestBid,bestAsk,mid,lastTrade,"
                    + "tradePrice,tradeQty,aggressor,passive,aggressorId,passiveId");
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
        row(timestamp, "BOOK", mid, Prices.NA, Prices.NA,
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
        if (agg == Bots.Owner.MAKER) makerAggTrades++;
        if (agg == Bots.Owner.TAKER) takerAggTrades++;

        row(e.timestamp(), "FILL", mid, e.priceUnits(), e.filledQuantity(),
                agg, pas, e.aggressorOrderId(), e.passiveOrderId());

        log.info("TRADE px={} qty={} aggressor={} passive={} (ids {}/{})",
                dollars(e.priceUnits()), e.filledQuantity(), agg, pas,
                e.aggressorOrderId(), e.passiveOrderId());
    }

    private void row(long tsNanos, String event, long mid, long tradePx, long tradeQty,
                     Bots.Owner agg, Bots.Owner pas, long aggId, long pasId) {
        try {
            out.write(tsNanos + "," + System.currentTimeMillis() + "," + event + ","
                    + bestBid + "," + bestAsk + "," + mid + "," + lastTrade + ","
                    + tradePx + "," + tradeQty + "," + agg + "," + pas + ","
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
        log.info("Summary: {} trades printed; maker-aggressed {}, taker-aggressed {}, other {}",
                trades, makerAggTrades, takerAggTrades, trades - makerAggTrades - takerAggTrades);
    }

    private static String dollars(long units) {
        return (units <= 0) ? "NA" : String.format("%.4f", units / (double) Prices.SCALE);
    }

    // Test accessors.
    long tradesForTest() { return trades; }
    long takerAggForTest() { return takerAggTrades; }
}
