package com.eventhub.booking.application.exception;

/** The gateway refused the charge; the booking stays PENDING and can be paid again (HTTP 402). */
public final class PaymentDeclinedException extends ApplicationException {

  public PaymentDeclinedException() {
    super("The payment was declined");
  }
}
