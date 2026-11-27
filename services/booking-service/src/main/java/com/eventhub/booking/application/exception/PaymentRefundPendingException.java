package com.eventhub.booking.application.exception;

/**
 * The charge was approved but the booking stopped being payable, and the refund has not been
 * completed yet (maps to HTTP 409 {@code payment-refund-pending}): the money is on its way back, a
 * reconciler finishes the refund.
 */
public final class PaymentRefundPendingException extends ApplicationException {

  public PaymentRefundPendingException() {
    super("The booking is no longer payable; the refund of the payment is pending");
  }
}
