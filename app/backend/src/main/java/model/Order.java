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

    /**
     * Legacy constructor (pre-Phase-14). Kept so no existing caller or test changes
     * meaning; defaults the time in force to GTC (Phase 14 decision J).
     */
    public Order(long orderID, long timeStamp, Side side, int quantity, long price, long participantID) {
        this(orderID, timeStamp, side, quantity, price, participantID, TimeInForce.GTC);
    }

    /**
     * Canonical constructor (Phase 14). Carries an explicit time in force. The constructor
     * is where domain validation fires (the gateway builds Orders here), so a null tif is
     * rejected exactly as a null side is.
     */
    public Order(long orderID, long timeStamp, Side side, int quantity, long price, long participantID, TimeInForce tif) {

        if (orderID <= 0) { throw new IllegalArgumentException("model.Order ID must be positive"); }
        if (timeStamp <= 0) { throw new IllegalArgumentException("Timestamp must be positive"); }
        if (participantID <= 0) { throw new IllegalArgumentException("Participant ID must be positive"); }
        if (quantity <= 0) { throw new IllegalArgumentException("Quantity must be positive"); }
        if (price <= 0) { throw new IllegalArgumentException("Price must be positive"); }
        if (!Prices.isOnTick(price)) { throw new IllegalArgumentException("Price must be on the one-cent tick"); }
        if (side == null) { throw new IllegalArgumentException("model.Side cannot be null"); }
        if (tif == null) { throw new IllegalArgumentException("Time in force cannot be null"); }

        this.orderID = orderID;
        this.timeStamp = timeStamp;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.status = Status.OPEN;
        this.participantID = participantID;
        this.tif = tif;
    }

    public void fill(int fillQty) {
        if (fillQty <= 0) { throw new IllegalArgumentException("Fill quantity must be positive"); }
        if (fillQty > this.quantity) { throw new IllegalArgumentException("Fill quantity exceeds remaining quantity"); }

        this.quantity -= fillQty;
        this.status = (this.quantity == 0) ? Status.FILLED : Status.PARTIALLY_FILLED;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
    }

    /**
     * Marks an unfilled (or partially filled) remainder as expired by time in force
     * (IOC/FOK, Phase 14). Mirrors {@link #cancel()}: a terminal status change only,
     * leaving {@code quantity} as the amount that expired unexecuted. Distinct from
     * CANCELLED so a trader can tell "you cancelled it" from "its time in force ended it".
     */
    public void expire() {
        this.status = Status.EXPIRED;
    }

    public long getOrderID() { return orderID; }
    public long getTimeStamp() { return timeStamp; }
    public Side getSide() { return side; }
    public int getQuantity() { return quantity; }
    public long getPrice() { return price; }
    public Status getStatus() { return status; }
    public long getParticipantID() { return participantID; }
    public TimeInForce getTimeInForce() { return tif; }

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
                '}';
    }
}
