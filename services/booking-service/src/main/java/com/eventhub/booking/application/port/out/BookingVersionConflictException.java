package com.eventhub.booking.application.port.out;

import java.util.UUID;

/** The booking changed since it was read (optimistic locking). Reload and decide again (409). */
public final class BookingVersionConflictException extends RuntimeException {

  public BookingVersionConflictException(UUID bookingId) {
    super("Booking %s was modified concurrently".formatted(bookingId));
  }
}
