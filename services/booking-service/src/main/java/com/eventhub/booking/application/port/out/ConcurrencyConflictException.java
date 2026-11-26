package com.eventhub.booking.application.port.out;

import java.util.UUID;

/**
 * A write lost a race: the aggregate changed since it was read (optimistic locking) or a database
 * constraint refused it. The same type is raised whether it is detected inside a transaction or
 * when it commits, so callers have one thing to handle: reload and decide again (HTTP 409).
 */
public final class ConcurrencyConflictException extends RuntimeException {

  public ConcurrencyConflictException(UUID bookingId) {
    super("Booking %s was modified concurrently".formatted(bookingId));
  }

  public ConcurrencyConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
