package com.eventhub.booking.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code occurredAt} is the creation instant. */
public record BookingCreated(
    UUID bookingId,
    UUID userId,
    UUID eventId,
    List<BookingItem> items,
    Money total,
    Instant expiresAt,
    Instant occurredAt)
    implements DomainEvent {}
