package com.eventhub.booking.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code outbox_event}; {@code payload} is the full envelope v1 as JSON text. */
public record OutboxMessage(
    UUID id,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    int eventVersion,
    String payload,
    UUID correlationId,
    Instant createdAt) {}
