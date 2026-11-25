package com.eventhub.booking.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Facts the aggregate records so the application layer can publish them via the outbox. Each one
 * maps 1:1 to a {@code booking-*.v1} schema in {@code contracts/events}.
 */
public sealed interface DomainEvent
    permits BookingCreated,
        BookingConfirmed,
        BookingExpired,
        BookingCancelled,
        BookingPaymentRefunded {

  UUID bookingId();

  Instant occurredAt();
}
