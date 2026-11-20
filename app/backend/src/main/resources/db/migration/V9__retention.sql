-- Database retention (P16-8, D14). Detail rows (orders, executions, order_audit) of business dates outside the
-- window are deleted in small batches, only for dates whose clearing run is COMPLETE. Clearing results,
-- balances, positions, candles and the per-date summary below are kept forever.

-- purged_ts: the transaction that wrote the date's daily_summary rows and started deleting its detail rows.
-- From then on the routes that read detail rows answer 410 PURGED for the date, so no reader ever sees a
-- partly deleted day. purge_completed_ts: the last batch for the date committed.
ALTER TABLE clearing_run
    ADD COLUMN purged_ts          bigint,      -- epoch nanos
    ADD COLUMN purge_completed_ts bigint;      -- epoch nanos

ALTER TABLE clearing_run ADD CONSTRAINT clearing_run_purge_complete_only CHECK (purged_ts IS NULL OR status = 'COMPLETE');
ALTER TABLE clearing_run ADD CONSTRAINT clearing_run_purge_order CHECK (purge_completed_ts IS NULL OR purged_ts IS NOT NULL);

-- What /api/reports/daily and /api/reports/volume read for a purged date: the same per-symbol aggregate they
-- compute from executions, written from executions in the transaction that sets purged_ts. notional is the
-- exact sum of price times quantity in $0.0001 units; an overflow raises SQLState 22003 rather than wrap.
CREATE TABLE daily_summary (
    business_date  int NOT NULL REFERENCES clearing_run (business_date),
    symbol         varchar(8) NOT NULL REFERENCES instrument (symbol),
    trade_count    bigint NOT NULL CHECK (trade_count > 0),
    volume         bigint NOT NULL CHECK (volume > 0),
    notional       bigint NOT NULL,             -- units of $0.0001 times quantity
    open           bigint NOT NULL,             -- units of $0.0001
    high           bigint NOT NULL,
    low            bigint NOT NULL,
    close          bigint NOT NULL,
    first_trade_ts bigint NOT NULL,             -- epoch nanos
    last_trade_ts  bigint NOT NULL,
    PRIMARY KEY (business_date, symbol)
);

-- Deleting by date. executions(business_date) and orders(business_date) exist (V3).
CREATE INDEX order_audit_business_date ON order_audit (business_date);

-- Every column that references orders is indexed, so the deferred foreign key checks and the purge's
-- reference checks look rows up by key. executions(order_inbound_seq) and order_audit(order_inbound_seq)
-- exist (V3); the passive side did not.
CREATE INDEX executions_passive_order_inbound_seq ON executions (passive_order_inbound_seq);

-- Rows outside any session (a command rejected while closed) have no business date; they are purged with the
-- first date whose close is after their timestamp. Rare, so these partial indexes stay tiny.
CREATE INDEX orders_undated ON orders (received_ts) WHERE business_date IS NULL;
CREATE INDEX executions_undated ON executions (event_ts) WHERE business_date IS NULL;
CREATE INDEX order_audit_undated ON order_audit (event_ts) WHERE business_date IS NULL;
