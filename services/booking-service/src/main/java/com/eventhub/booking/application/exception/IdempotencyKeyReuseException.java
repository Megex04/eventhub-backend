package com.eventhub.booking.application.exception;

import java.util.UUID;

/** The Idempotency-Key was already used with a different request body (maps to HTTP 409). */
public final class IdempotencyKeyReuseException extends ApplicationException {

  public IdempotencyKeyReuseException(UUID key) {
    super("Idempotency-Key %s was already used with a different request".formatted(key));
  }
}
