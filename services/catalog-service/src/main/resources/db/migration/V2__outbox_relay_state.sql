-- Relay bookkeeping for the transactional outbox (milestone 6).
--
-- next_attempt_at: earliest moment the relay may try the row again. The writer sets it to
-- created_at, so a new row is due at once; a failed attempt pushes it forward (exponential backoff).
-- last_error: why the last attempt failed, for diagnosis only (never read by the relay).
-- The attempts cap needs no column: a row with attempts >= the configured maximum is "parked".
ALTER TABLE outbox_event
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN last_error      text;

UPDATE outbox_event SET next_attempt_at = created_at;

ALTER TABLE outbox_event ALTER COLUMN next_attempt_at SET NOT NULL;

-- Per-aggregate ordering: the relay sends a row only if no earlier row of the same aggregate is
-- still unpublished (so a publish that is backing off is never overtaken by its reschedule). That
-- check is an index lookup by aggregate over unpublished rows only, which this partial index keeps
-- small: published rows leave it, and the pending-rows index above does not start with aggregate_id.
CREATE INDEX idx_outbox_event_unpublished_by_aggregate
    ON outbox_event (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
