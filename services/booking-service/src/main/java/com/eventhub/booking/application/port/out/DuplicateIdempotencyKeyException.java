package com.eventhub.booking.application.port.out;

import java.util.UUID;

/** Another request already stored a booking for this user and Idempotency-Key. */
public final class DuplicateIdempotencyKeyException extends RuntimeException {

  public DuplicateIdempotencyKeyException(UUID userId, UUID key) {
    super("Idempotency-Key %s was already used by user %s".formatted(key, userId));
  }
}
