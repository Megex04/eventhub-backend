package com.eventhub.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** Always carries the resulting venue, even when the request kept the previous one. */
public record EventRescheduled(
    UUID eventId, Instant startsAt, Instant endsAt, String venue, Instant occurredAt)
    implements DomainEvent {}
