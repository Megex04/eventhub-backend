package com.eventhub.booking.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code occurredAt} is the instant the refund completed (the contract's {@code refundedAt}). */
public record BookingPaymentRefunded(
    UUID bookingId,
    UUID userId,
    UUID eventId,
    UUID paymentId,
    Money amount,
    RefundReason reason,
    Instant occurredAt)
    implements DomainEvent {}
