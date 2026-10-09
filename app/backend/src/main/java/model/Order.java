package model;

public class Order {

    private long orderID;
    private long timeStamp;
    private Side side;
    private int quantity;
    private long price;
    private Status status;
    private long participantID;
    private TimeInForce tif;
    private int peak;         // iceberg display size (FIX 111 MaxFloor); 0 = not an iceberg (Phase 14)
    private int visibleQty;   // currently displayed slice; always == quantity for a plain order
    private OrdType ordType;  // LIMIT or PEG_MID (FIX 40); a peg carries price == Prices.NA (Phase 14)

    /**
     * Legacy constructor (pre-Phase-14). Kept so no existing caller or test changes
     * meaning; defaults the time in force to GTC, the display size to 0 (a plain order)
     * and the order type to LIMIT (Phase 14 decision J). Delegates to the canonical constructor.
     */
    public Order(long orderID, long timeStamp, Side side, int quantity, long price, long participantID) {
        this(orderID, timeStamp, side, quantity, price, participantID, TimeInForce.GTC, 0, OrdType.LIMIT);
    }

    /**
     * TIF constructor (P14-4). Kept as a delegating overload so callers that carry a time in
     * force but no display size, the pre-P14-7 canonical form, stay unchanged; defaults the
     * display size to 0 and the order type to LIMIT (Phase 14 decision J). Delegates to the
     * canonical constructor.
     */
    public Order(long orderID, long timeStamp, Side side, int quantity, long price, long participantID,
                 TimeInForce tif) {
        this(orderID, timeStamp, side, quantity, price, participantID, tif, 0, OrdType.LIMIT);
    }

    /**
     * TIF + display-size constructor (P14-7). Kept as a delegating overload so callers that carry
     * a time in force and an iceberg display size but no explicit order type, the pre-P14-9
     * canonical form, stay unchanged; defaults the order type to LIMIT (Phase 14 decision J).
     * Delegates to the canonical constructor.
     */
    public Order(long orderID, long timeStamp, Side side, int quantity, long price, long participantID,
                 TimeInForce tif, int peak) {
        this(orderID, timeStamp, side, quantity, price, participantID, tif, peak, OrdType.LIMIT);
    }

    /**
     * Canonical constructor (Phase 14-9). Carries an explicit time in force, an iceberg display
     * size, and an order type. The constructor is where domain validation fires (the gateway
     * builds Orders here), so a null field and the type/display combination rules are rejected
     * here and surface as ORDER_REJECTED through the handler's IllegalArgumentException path
     * (SRS 3.1 / 3.3).
     *
     * <p>Price by type (D1 / D9): a LIMIT order must have a strictly positive price on the
     * one-cent tick; a PEG_MID order carries no price of its own and must be constructed with
     * {@link Prices#NA}, because its executions happen only at the lit-book midpoint.
     *
     * <p>Iceberg rules (D7 / D8): the display size must satisfy 0 &lt;= peak &lt;= quantity; a
     * peak equal to the quantity hides nothing and is normalised to 0, a plain order, BEFORE the
     * time-in-force rule, so a peak==quantity IOC or FOK order is a plain IOC/FOK order rather
     * than a reject; and a genuine iceberg (peak &gt; 0 after normalisation) must be GTC, because
     * a display size has no meaning for an order that never rests. A PEG_MID order cannot be an
     * iceberg (peak must be 0), because a non-displayed order has nothing to display (decision E).
     */
    public Order(long orderID, long timeStamp, Side side, int quantity, long price, long participantID,
                 TimeInForce tif, int peak, OrdType ordType) {

        if (orderID <= 0) { throw new IllegalArgumentException("model.Order ID must be positive"); }
        if (timeStamp <= 0) { throw new IllegalArgumentException("Timestamp must be positive"); }
        if (participantID <= 0) { throw new IllegalArgumentException("Participant ID must be positive"); }
        if (quantity <= 0) { throw new IllegalArgumentException("Quantity must be positive"); }
        if (side == null) { throw new IllegalArgumentException("model.Side cannot be null"); }
        if (tif == null) { throw new IllegalArgumentException("Time in force cannot be null"); }
        if (ordType == null) { throw new IllegalArgumentException("Order type cannot be null"); }

        // Price validation by order type (Phase 14 D1 / D9 / decision E).
        if (ordType == OrdType.LIMIT) {
            if (price <= 0) { throw new IllegalArgumentException("Price must be positive"); }
            if (!Prices.isOnTick(price)) { throw new IllegalArgumentException("Price must be on the one-cent tick"); }
        } else { // PEG_MID
            if (price != Prices.NA) { throw new IllegalArgumentException("A midpoint peg order carries no price (expected Prices.NA)"); }
            if (peak != 0) { throw new IllegalArgumentException("A midpoint peg order cannot be an iceberg"); }
        }

        if (peak < 0) { throw new IllegalArgumentException("Display quantity cannot be negative"); }
        if (peak > quantity) { throw new IllegalArgumentException("Display quantity cannot exceed order quantity"); }
        if (peak == quantity) { peak = 0; }   // hides nothing -> plain order, normalised BEFORE the GTC check
        if (peak > 0 && tif != TimeInForce.GTC) {
            throw new IllegalArgumentException("An iceberg order must be GTC");
        }

        this.orderID = orderID;
        this.timeStamp = timeStamp;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.status = Status.OPEN;
        this.participantID = participantID;
        this.tif = tif;
        this.peak = peak;
        this.ordType = ordType;
        refreshDisplay();   // initialise visibleQty consistently (== quantity for a plain order / peg)
    }

    public void fill(int fillQty) {
        if (fillQty <= 0) { throw new IllegalArgumentException("Fill quantity must be positive"); }
        if (fillQty > this.quantity) { throw new IllegalArgumentException("Fill quantity exceeds remaining quantity"); }

        int visibleReduction = Math.min(fillQty, this.visibleQty);   // computed before mutating quantity
        this.quantity -= fillQty;
        this.visibleQty -= visibleReduction;
        this.status = (this.quantity == 0) ? Status.FILLED : Status.PARTIALLY_FILLED;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
    }

    /**
     * Marks an unfilled (or partially filled) remainder as expired by time in force
     * (IOC/FOK, Phase 14). Mirrors {@link #cancel()}: a terminal status change only,
     * leaving {@code quantity} as the amount that expired. Distinct from
     * CANCELLED so a trader can tell "you cancelled it" from "its time in force ended it".
     */
    public void expire() {
        this.status = Status.EXPIRED;
    }

    /**
     * Recomputes the displayed slice from the current remaining quantity and the display size
     * (Phase 14 D7): the smaller of the peak and the remaining quantity for an iceberg, the full
     * remaining quantity for a plain order. Called at construction, when the order rests, and on
     * each reload, so {@code visibleQty} always equals {@code quantity} for a plain order and
     * never exceeds it for an iceberg. A peg carries peak 0, so its visible slice equals its
     * quantity (it is simply never read, because pegs are not in the displayed book).
     */
    public void refreshDisplay() {
        this.visibleQty = (peak > 0) ? Math.min(peak, quantity) : quantity;
    }

    public long getOrderID() { return orderID; }
    public long getTimeStamp() { return timeStamp; }
    public Side getSide() { return side; }
    public int getQuantity() { return quantity; }
    public long getPrice() { return price; }
    public Status getStatus() { return status; }
    public long getParticipantID() { return participantID; }
    public TimeInForce getTimeInForce() { return tif; }
    public int getPeak() { return peak; }
    public int getVisibleQty() { return visibleQty; }
    public OrdType getOrdType() { return ordType; }

    @Override
    public String toString() {
        return "model.Order{" +
                "orderID=" + orderID +
                ", timeStamp=" + timeStamp +
                ", side='" + side + '\'' +
                ", quantity=" + quantity +
                ", price=" + price +
                ", status='" + status + '\'' +
                ", participantID=" + participantID +
                ", tif=" + tif +
                ", peak=" + peak +
                ", visibleQty=" + visibleQty +
                ", ordType=" + ordType +
                '}';
    }
}
