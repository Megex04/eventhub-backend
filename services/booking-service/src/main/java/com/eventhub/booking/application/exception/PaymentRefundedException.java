package com.eventhub.booking.application.exception;

/**
 * The charge was approved but the booking stopped being payable meanwhile, so the money is being
 * given back (maps to HTTP 409 {@code payment-refunded}). The refund itself may still be pending.
 */
public final class PaymentRefundedException extends ApplicationException {

  public PaymentRefundedException() {
    super("The booking is no longer payable; the payment is being refunded");
  }
}
