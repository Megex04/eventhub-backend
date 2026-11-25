package com.eventhub.booking.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code occurredAt} is the expiry instant (the contract's {@code expiredAt}). */
public record BookingExpired(UUID bookingId, UUID userId, UUID eventId, Instant occurredAt)
    implements DomainEvent {}
