package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.Money;
import java.util.UUID;

/**
 * {@code paymentId} is the idempotency key: repeating the call never charges twice. The token is
 * opaque ({@code tok_...}) and is never printed, so a stray log line cannot leak it.
 */
public record ChargeRequest(UUID paymentId, Money amount, String paymentToken) {

  @Override
  public String toString() {
    return "ChargeRequest[paymentId=%s, amount=%s, paymentToken=***]".formatted(paymentId, amount);
  }
}
