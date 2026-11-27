package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.domain.Booking;
import java.util.UUID;

/** A booking of another user is reported as missing, so ids cannot be probed. */
final class OwnedBooking {

  private OwnedBooking() {}

  static Booking find(BookingRepository bookings, UUID userId, UUID bookingId) {
    return bookings
        .findById(bookingId)
        .filter(booking -> booking.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Booking", bookingId));
  }
}
