package com.eventhub.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** Facts the aggregate records so the application layer can publish them via the outbox. */
public sealed interface DomainEvent permits EventPublished, EventCancelled, EventRescheduled {

  UUID eventId();

  Instant occurredAt();
}
