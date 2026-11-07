-- End-of-day clearing as built (P15-11).
--
-- The Reporter inserts a PENDING run in the same transaction that consumes SESSION_CLOSED and moves its
-- checkpoint past it, so a run exists exactly when every execution of its date is in this database.
-- Clearing completes a run in one transaction, so a run is never seen half done and STARTED is not needed.

ALTER TABLE clearing_run DROP CONSTRAINT clearing_run_status_check;
ALTER TABLE clearing_run ADD CONSTRAINT clearing_run_status_check CHECK (status IN ('PENDING', 'COMPLETE'));

-- started_ts is set when clearing runs, not when the Reporter records the close.
ALTER TABLE clearing_run ALTER COLUMN started_ts DROP NOT NULL;

ALTER TABLE clearing_run
    ADD COLUMN closed_ts          bigint NOT NULL,      -- epoch nanos, the SESSION_CLOSED event's timestamp
    ADD COLUMN next_business_date int CHECK (next_business_date BETWEEN 19700101 AND 99991231),
    ADD COLUMN fill_count         bigint CHECK (fill_count >= 0),
    ADD COLUMN volume             bigint CHECK (volume >= 0);

-- A complete run says when it ran, which date it rolled to and what it netted.
ALTER TABLE clearing_run ADD CONSTRAINT clearing_run_complete_check CHECK (
    status = 'PENDING'
    OR (started_ts IS NOT NULL AND completed_ts IS NOT NULL AND next_business_date > business_date
        AND fill_count IS NOT NULL AND volume IS NOT NULL));

ALTER TABLE clearing_obligation ADD CONSTRAINT clearing_obligation_quantities_check CHECK (
    bought_quantity >= 0 AND sold_quantity >= 0 AND net_quantity = bought_quantity - sold_quantity);
