package com.eventhub.booking.application.port.out;

/** The gateway could not be reached or answered in time: the outcome of the call is unknown. */
public final class PaymentGatewayException extends RuntimeException {

  public PaymentGatewayException(String message) {
    super(message);
  }

  public PaymentGatewayException(String message, Throwable cause) {
    super(message, cause);
  }
}
