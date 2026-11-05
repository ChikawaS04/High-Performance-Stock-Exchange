-- One-minute candles and the Reporter's checkpoint (SRS 3.11, 3.13).

CREATE TABLE candle (
    symbol            varchar(8) NOT NULL REFERENCES instrument (symbol),
    bucket_start_ts   bigint NOT NULL,      -- epoch nanos of the minute's start
    business_date     int NOT NULL CHECK (business_date BETWEEN 19700101 AND 99991231),
    open              bigint NOT NULL,      -- units of $0.0001
    high              bigint NOT NULL,
    low               bigint NOT NULL,
    close             bigint NOT NULL,
    volume            bigint NOT NULL,
    trade_count       bigint NOT NULL,
    last_outbound_seq bigint NOT NULL,      -- a fill at or below this seq is already included
    PRIMARY KEY (symbol, bucket_start_ts)
);

CREATE INDEX candle_business_date ON candle (business_date);

-- A single row, written in the same transaction as the rows it covers.
CREATE TABLE reporter_checkpoint (
    id                smallint PRIMARY KEY CHECK (id = 1),
    last_inbound_seq  bigint NOT NULL,
    last_outbound_seq bigint NOT NULL
);

INSERT INTO reporter_checkpoint (id, last_inbound_seq, last_outbound_seq) VALUES (1, 0, 0);
