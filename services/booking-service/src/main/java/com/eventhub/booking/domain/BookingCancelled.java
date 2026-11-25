package com.eventhub.booking.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code occurredAt} is the cancellation instant (the contract's {@code cancelledAt}). */
public record BookingCancelled(
    UUID bookingId, UUID userId, UUID eventId, CancelReason reason, Instant occurredAt)
    implements DomainEvent {}
