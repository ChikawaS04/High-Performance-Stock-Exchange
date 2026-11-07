package reporter;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of one transaction, in journal order. One instance is reused for the Reporter's life, so
 * allocation is bounded per batch: a few small records per event, and at most
 * {@link ReporterTiming#maxBatchEvents()} events. Reporter thread only.
 */
public final class ReporterBatch {

    /** A NEW command. Nullable values are SQL NULL. */
    public record OrderRow(long inboundSeq, long orderId, long participantId, Integer businessDate, String symbol,
                           String side, String ordType, String timeInForce, Long limitPrice, long quantity,
                           Long displayQuantity, long receivedTs) {
    }

    /** A status and cumulative quantity as of one outbound seq. */
    public record OrderUpdate(long inboundSeq, String status, long cumQuantity, long outboundSeq) {
    }

    /** An order execution event. */
    public record ExecutionRow(long outboundSeq, String eventType, Long orderInboundSeq, long orderId, Long tradeId,
                               Long price, Long filledQuantity, Long remainingQuantity, Long aggressorOrderId,
                               Long passiveOrderId, Long passiveOrderInboundSeq, String aggressorSide,
                               Long aggressorParticipantId, Long passiveParticipantId, Integer businessDate,
                               long eventTs) {
    }

    /** A command or event concerning an order. */
    public record AuditRow(String stream, long seq, Long orderInboundSeq, long orderId, String event,
                           Integer businessDate, long eventTs, Long price, Long quantity, Long remainingQuantity) {
    }

    /** One fill's contribution to a candle. */
    public record CandleFill(String symbol, int businessDate, long bucketStartTs, long price, long quantity,
                             long outboundSeq) {
    }

    /**
     * A closed business date whose clearing run may start once this batch commits (P15-11).
     *
     * @param closeOutboundSeq the SESSION_CLOSED event's seq
     * @param closedTs         that event's timestamp, epoch nanos
     */
    public record PendingRun(int businessDate, long closeOutboundSeq, long closedTs) {
    }

    public final List<OrderRow> orders = new ArrayList<>();
    public final List<OrderUpdate> orderUpdates = new ArrayList<>();
    public final List<ExecutionRow> executions = new ArrayList<>();
    public final List<AuditRow> audit = new ArrayList<>();
    public final List<CandleFill> candleFills = new ArrayList<>();
    public final List<PendingRun> pendingRuns = new ArrayList<>();

    /** Outbound events in this batch, session events included: they advance the checkpoint too. */
    private int events;
    private long firstEventNanos;

    void eventAdded(long nowNanos) {
        if (events == 0) {
            firstEventNanos = nowNanos;
        }
        events++;
    }

    public int events() {
        return events;
    }

    /** Whether the batch has reached a bound. An empty batch never has. */
    boolean due(ReporterTiming timing, long nowNanos) {
        return events > 0
                && (events >= timing.maxBatchEvents() || nowNanos - firstEventNanos >= timing.maxBatchAgeMs() * 1_000_000L);
    }

    public void clear() {
        orders.clear();
        orderUpdates.clear();
        executions.clear();
        audit.clear();
        candleFills.clear();
        pendingRuns.clear();
        events = 0;
    }
}
