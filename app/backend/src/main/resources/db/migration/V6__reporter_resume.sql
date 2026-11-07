-- What the Reporter needs to resume without scanning the journal (P15-10).

-- Replay needs positions, not seqs, so the checkpoint holds both. Session state is held too, because
-- business_date comes from the preceding SESSION_OPENED, which a resume does not re-read; NULL means
-- no open session.
--
-- The recording ids and start timestamps name the journal this checkpoint belongs to; NULL until the
-- first commit. The ids alone are not enough: the Archive numbers recordings from 0 in each new
-- catalog, so every fresh journal has the same ids.
ALTER TABLE reporter_checkpoint
    ADD COLUMN inbound_position        bigint NOT NULL DEFAULT 0,
    ADD COLUMN outbound_position       bigint NOT NULL DEFAULT 0,
    ADD COLUMN session_business_date   int CHECK (session_business_date BETWEEN 19700101 AND 99991231),
    ADD COLUMN session_symbol          varchar(8) REFERENCES instrument (symbol),
    ADD COLUMN inbound_recording_id    bigint,
    ADD COLUMN inbound_recording_ts    bigint,
    ADD COLUMN outbound_recording_id   bigint,
    ADD COLUMN outbound_recording_ts   bigint;

-- A minute can hold fills of two business dates: a day closes and the next opens within a second or
-- two of midnight, so per-date candles must not share a row.
ALTER TABLE candle DROP CONSTRAINT candle_pkey;
ALTER TABLE candle ADD PRIMARY KEY (symbol, business_date, bucket_start_ts);

-- A resume loads the orders still resting: this keeps that proportional to them, not to history.
CREATE INDEX orders_open ON orders (inbound_seq) WHERE status IN ('OPEN', 'PARTIALLY_FILLED');
