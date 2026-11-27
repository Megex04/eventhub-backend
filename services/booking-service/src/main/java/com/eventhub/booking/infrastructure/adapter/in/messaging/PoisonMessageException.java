package com.eventhub.booking.infrastructure.adapter.in.messaging;

/**
 * A message that can never be processed, however often it is retried: not JSON, a required field
 * missing, an unknown type or an unsupported version. It is configured as not retryable, so it goes
 * straight to the dead-letter queue and never holds up the messages behind it.
 */
public final class PoisonMessageException extends RuntimeException {

  public PoisonMessageException(String message) {
    super(message);
  }

  public PoisonMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
