-- End-of-day clearing (SRS 3.12, 3.13). Idempotent per business date.

CREATE TABLE clearing_run (
    business_date      int PRIMARY KEY CHECK (business_date BETWEEN 19700101 AND 99991231),
    status             varchar(8) NOT NULL CHECK (status IN ('STARTED', 'COMPLETE')),
    close_outbound_seq bigint NOT NULL,     -- the SESSION_CLOSED event's seq
    started_ts         bigint NOT NULL,     -- epoch nanos
    completed_ts       bigint               -- epoch nanos; NULL until COMPLETE
);

CREATE TABLE clearing_obligation (
    business_date   int NOT NULL REFERENCES clearing_run (business_date),
    participant_id  bigint NOT NULL REFERENCES participant (participant_id),
    symbol          varchar(8) NOT NULL REFERENCES instrument (symbol),
    bought_quantity bigint NOT NULL,
    sold_quantity   bigint NOT NULL,
    net_quantity    bigint NOT NULL,
    cash_obligation bigint NOT NULL,        -- units of $0.0001
    PRIMARY KEY (business_date, participant_id, symbol)
);
