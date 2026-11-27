package com.eventhub.booking.domain;

/** Why a captured payment is given back; the values of the {@code BookingPaymentRefunded} event. */
public enum RefundReason {
  BOOKING_EXPIRED_DURING_PAYMENT,
  BOOKING_CANCELLED,
  EVENT_CANCELLED
}
