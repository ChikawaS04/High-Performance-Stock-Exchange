-- Reports and /api (P15-12).
--
-- Trade history and participant statements page through one business date's fills in outbound seq order
-- (keyset on outbound_seq). Without this index each page reads every execution of the date and sorts;
-- with it a page reads about as many rows as it returns. A fill is the only execution with a trade id,
-- so the index holds fills only.
CREATE INDEX executions_fills_by_date ON executions (business_date, outbound_seq) WHERE trade_id IS NOT NULL;
