package com.eventhub.booking.domain;

/**
 * {@code INITIATED} and {@code DECLINED} are internal to the saga; the API only shows {@code
 * CAPTURED}, {@code REFUND_PENDING} and {@code REFUNDED}.
 */
public enum PaymentStatus {
  INITIATED,
  DECLINED,
  CAPTURED,
  REFUND_PENDING,
  REFUNDED
}
