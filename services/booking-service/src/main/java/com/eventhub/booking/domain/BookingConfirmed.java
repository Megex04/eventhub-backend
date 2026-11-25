package com.eventhub.booking.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code occurredAt} is the confirmation instant (the contract's {@code confirmedAt}). */
public record BookingConfirmed(
    UUID bookingId, UUID userId, UUID eventId, UUID paymentId, Money total, Instant occurredAt)
    implements DomainEvent {}
