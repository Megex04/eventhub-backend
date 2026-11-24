-- catalog-service schema, v1. Flyway owns the schema; Hibernate only validates it.
-- All instants are timestamptz (UTC, microsecond precision).

CREATE TABLE event (
    id            uuid          PRIMARY KEY,
    organizer_id  uuid          NOT NULL,
    title         varchar(120)  NOT NULL,
    description   varchar(4000) NOT NULL DEFAULT '',
    venue         varchar(160)  NOT NULL,
    starts_at     timestamptz   NOT NULL,
    ends_at       timestamptz   NOT NULL,
    status        varchar(16)   NOT NULL,
    cancel_reason varchar(500),
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL,
    -- Optimistic-locking token (JPA @Version): a stale write updates 0 rows and fails.
    version       bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'FINISHED')),
    CONSTRAINT ck_event_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_event_title_length CHECK (char_length(title) >= 3),
    CONSTRAINT ck_event_venue_length CHECK (char_length(venue) >= 2),
    -- A cancel reason exists exactly when the event is CANCELLED (CANCELLED is terminal).
    CONSTRAINT ck_event_cancel_reason CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL))
);

-- Public listing is the hot path: PUBLISHED events ordered by start date. A partial index keeps it
-- small (drafts and cancelled events are excluded) and serves ORDER BY starts_at, id directly.
CREATE INDEX idx_event_published_starts_at ON event (starts_at, id) WHERE status = 'PUBLISHED';

-- "mine" and the organizer's view of their own non-published events filter by organizer_id and
-- sort by start date.
CREATE INDEX idx_event_organizer_starts_at ON event (organizer_id, starts_at, id);

-- No trigram index for the free-text filter: ILIKE with a leading wildcard cannot use a btree
-- index. Add pg_trgm only if profiling shows the scan matters.

CREATE TABLE ticket_zone (
    id             uuid          PRIMARY KEY,
    event_id       uuid          NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- Preserves the order zones have in the aggregate; rewritten from the list index on save.
    position       smallint      NOT NULL,
    name           varchar(60)   NOT NULL,
    price_amount   numeric(12,2) NOT NULL,
    price_currency varchar(3)    NOT NULL,
    capacity       integer       NOT NULL,
    -- Zone names are unique per event ignoring case, like TicketZone.hasName in the domain.
    name_key       text GENERATED ALWAYS AS (lower(name)) STORED,

    CONSTRAINT ck_zone_price CHECK (price_amount >= 0),
    CONSTRAINT ck_zone_currency CHECK (price_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_zone_capacity CHECK (capacity BETWEEN 1 AND 100000),
    -- Deferred so one save can remove a zone and add another with the same name: Hibernate runs
    -- inserts before deletes, and a plain unique index cannot be deferred.
    CONSTRAINT uq_zone_event_name UNIQUE (event_id, name_key) DEFERRABLE INITIALLY DEFERRED
);

-- Loading an event's zones in order. The unique constraint above already indexes
-- (event_id, name_key), which covers the foreign key but not the ordering.
CREATE INDEX idx_ticket_zone_event_position ON ticket_zone (event_id, position);

CREATE TABLE outbox_event (
    id             uuid        PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    event_version  integer     NOT NULL,
    -- The complete envelope v1, exactly as it will be published.
    payload        jsonb       NOT NULL,
    correlation_id uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0
);

-- The publisher polls unpublished rows oldest first (FOR UPDATE SKIP LOCKED). Published rows
-- vastly outnumber pending ones, so a partial index keeps the poll cheap as the table grows.
CREATE INDEX idx_outbox_event_pending ON outbox_event (created_at, id) WHERE published_at IS NULL;
