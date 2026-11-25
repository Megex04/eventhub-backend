package com.eventhub.booking.application.port.out;

/**
 * The payment provider, always simulated in this project. Both operations are idempotent by {@code
 * paymentId}. An unknown outcome (timeout, connection error) is a {@link PaymentGatewayException},
 * never a decline: the caller must not assume the money did or did not move.
 */
public interface PaymentGateway {

  ChargeResult charge(ChargeRequest request);

  void refund(RefundRequest request);
}
