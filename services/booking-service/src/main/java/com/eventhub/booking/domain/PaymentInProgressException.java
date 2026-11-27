package com.eventhub.booking.domain;

import java.util.UUID;

/**
 * A payment attempt for this booking is already running or settled (maps to HTTP 409 {@code
 * payment-in-progress}). The database backs it with a unique partial index, so two concurrent
 * attempts cannot both be recorded.
 */
public final class PaymentInProgressException extends DomainException {

  private final UUID paymentId;

  public PaymentInProgressException(UUID paymentId) {
    super("Payment %s is already in progress for this booking".formatted(paymentId));
    this.paymentId = paymentId;
  }

  public UUID paymentId() {
    return paymentId;
  }
}
