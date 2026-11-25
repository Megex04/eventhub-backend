package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.Money;
import java.util.UUID;

/** {@code paymentId} is the idempotency key: repeating the call never charges twice. */
public record ChargeRequest(UUID paymentId, Money amount, String paymentToken) {}
