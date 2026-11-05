-- Trading record (SRS 3.11, 3.13), written by the Reporter from the recorded streams. Every key is a
-- journal sequence number, so applying the same event twice has no effect.
--
-- Foreign keys between these tables are deferred to commit: the Reporter writes a batch read from
-- both recordings in one transaction, in either order.
--
-- business_date and symbol are NULL for a command outside any session: a NEW rejected while closed
-- belongs to no business date, and a NEW does not carry a symbol.

-- One row per NEW command. Client order ids may be reused once an order stops resting (D3), so
-- order_id is not a key.
CREATE TABLE orders (
    inbound_seq       bigint PRIMARY KEY CHECK (inbound_seq > 0),
    order_id          bigint NOT NULL,
    participant_id    bigint NOT NULL REFERENCES participant (participant_id),
    business_date     int CHECK (business_date BETWEEN 19700101 AND 99991231),
    symbol            varchar(8) REFERENCES instrument (symbol),
    side              varchar(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    ord_type          varchar(8) NOT NULL CHECK (ord_type IN ('LIMIT', 'PEG_MID')),
    time_in_force     varchar(3) NOT NULL CHECK (time_in_force IN ('GTC', 'IOC', 'FOK')),
    limit_price       bigint,               -- units of $0.0001; NULL for a pegged order
    quantity          bigint NOT NULL,
    display_quantity  bigint,               -- NULL when not an iceberg
    received_ts       bigint NOT NULL,      -- epoch nanos, the journaled command's timestamp
    -- Maintained from the outbound stream; an update applies only when its seq is greater.
    status            varchar(16) CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED',
                          'EXPIRED', 'REJECTED')),
    cum_quantity      bigint NOT NULL DEFAULT 0,
    last_outbound_seq bigint NOT NULL DEFAULT 0
);

CREATE INDEX orders_order_id ON orders (order_id, inbound_seq);
CREATE INDEX orders_business_date ON orders (business_date);

-- One row per order execution event; session events are not executions.
CREATE TABLE executions (
    outbound_seq              bigint PRIMARY KEY CHECK (outbound_seq > 0),
    event_type                varchar(24) NOT NULL CHECK (event_type IN ('ORDER_ACCEPTED', 'ORDER_FILLED',
                                  'ORDER_PARTIALLY_FILLED', 'ORDER_CANCELLED', 'ORDER_REJECTED', 'ORDER_EXPIRED')),
    -- The order this report is about, never resolved through a reused order id. NULL for a rejected
    -- CANCEL: the engine rejects one only when the id is not resting, so no order row is its subject.
    order_inbound_seq         bigint REFERENCES orders (inbound_seq) DEFERRABLE INITIALLY DEFERRED,
    order_id                  bigint NOT NULL,
    trade_id                  bigint,       -- fills only
    price                     bigint,       -- units of $0.0001
    filled_quantity           bigint,
    remaining_quantity        bigint,
    aggressor_order_id        bigint,
    passive_order_id          bigint,
    passive_order_inbound_seq bigint REFERENCES orders (inbound_seq) DEFERRABLE INITIALLY DEFERRED,
    aggressor_side            varchar(4) CHECK (aggressor_side IN ('BUY', 'SELL')),
    aggressor_participant_id  bigint REFERENCES participant (participant_id),
    passive_participant_id    bigint REFERENCES participant (participant_id),
    business_date             int CHECK (business_date BETWEEN 19700101 AND 99991231),
    event_ts                  bigint NOT NULL   -- epoch nanos
);

-- The engine owns the trade id counter and never reuses an id.
CREATE UNIQUE INDEX executions_trade_id ON executions (trade_id) WHERE trade_id IS NOT NULL;
CREATE INDEX executions_business_date ON executions (business_date);
CREATE INDEX executions_order_inbound_seq ON executions (order_inbound_seq);

-- Every command and every event concerning an order, keyed by the stream and seq it came from.
CREATE TABLE order_audit (
    stream             varchar(3) NOT NULL CHECK (stream IN ('IN', 'OUT')),
    seq                bigint NOT NULL CHECK (seq > 0),
    order_inbound_seq  bigint REFERENCES orders (inbound_seq) DEFERRABLE INITIALLY DEFERRED,  -- NULL as in executions
    order_id           bigint NOT NULL,
    event              varchar(24) NOT NULL CHECK (event IN ('NEW_ORDER', 'CANCEL_ORDER', 'ORDER_ACCEPTED',
                           'ORDER_FILLED', 'ORDER_PARTIALLY_FILLED', 'ORDER_CANCELLED', 'ORDER_REJECTED',
                           'ORDER_EXPIRED')),
    business_date      int CHECK (business_date BETWEEN 19700101 AND 99991231),
    event_ts           bigint NOT NULL,     -- epoch nanos
    price              bigint,              -- units of $0.0001
    quantity           bigint,
    remaining_quantity bigint,
    PRIMARY KEY (stream, seq)
);

CREATE INDEX order_audit_order_inbound_seq ON order_audit (order_inbound_seq);
