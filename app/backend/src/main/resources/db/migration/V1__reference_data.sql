-- Reference data (SRS 3.12, 3.13). Read at session boundaries; the engine never reads it.

-- One row per tradable instrument. The session controller carries these values inside SESSION_OPEN.
CREATE TABLE instrument (
    symbol      varchar(8) PRIMARY KEY CHECK (symbol ~ '^[A-Z0-9.]{1,8}$'),
    tick_size   bigint NOT NULL CHECK (tick_size > 0),      -- units of $0.0001
    lot_size    bigint NOT NULL CHECK (lot_size > 0),
    price_scale bigint NOT NULL CHECK (price_scale > 0)     -- units per dollar
);

-- Matches the configuration defaults (config.ExchangeConfig).
INSERT INTO instrument (symbol, tick_size, lot_size, price_scale) VALUES ('ASML', 100, 1, 10000);

-- Exceptions to the calendar. A date with no row is a trading day, so an empty table trades every day.
CREATE TABLE trading_calendar (
    business_date  int PRIMARY KEY CHECK (business_date BETWEEN 19700101 AND 99991231),
    is_trading_day boolean NOT NULL,
    description    text
);

CREATE TABLE participant (
    participant_id   bigint PRIMARY KEY CHECK (participant_id > 0),
    name             text NOT NULL,
    participant_type varchar(8) NOT NULL CHECK (participant_type IN ('INTERNAL', 'EXTERNAL'))
);

-- The owner's terminal is the single internal participant (SRS 3.12).
CREATE UNIQUE INDEX participant_one_internal ON participant (participant_type) WHERE participant_type = 'INTERNAL';

INSERT INTO participant (participant_id, name, participant_type) VALUES (1, 'House terminal', 'INTERNAL');
