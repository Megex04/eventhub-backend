package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.Money;
import java.util.UUID;

/** {@code paymentId} is the idempotency key of the refund, in a key space apart from charges. */
public record RefundRequest(UUID paymentId, Money amount) {}
