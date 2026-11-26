-- The outbox publisher gives every new row a created_at strictly after the previous row of the same
-- booking (max(created_at) of the aggregate + 1 microsecond when the clock would tie or go back), so
-- the relay's created_at, id order is the order the events happened in. Published rows count too,
-- which the partial index of V1 (unpublished only) cannot serve.
CREATE INDEX idx_outbox_event_by_aggregate ON outbox_event (aggregate_id, created_at DESC);
