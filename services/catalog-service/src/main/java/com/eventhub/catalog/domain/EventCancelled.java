package com.eventhub.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code occurredAt} is the cancellation instant (the contract's {@code cancelledAt}). */
public record EventCancelled(UUID eventId, String reason, Instant occurredAt)
    implements DomainEvent {}
