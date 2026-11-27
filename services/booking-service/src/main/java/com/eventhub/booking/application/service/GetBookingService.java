package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.in.GetBookingUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.domain.Booking;
import java.util.UUID;

public class GetBookingService implements GetBookingUseCase {

  private final BookingRepository bookings;

  public GetBookingService(BookingRepository bookings) {
    this.bookings = bookings;
  }

  @Override
  public Booking get(UUID userId, UUID bookingId) {
    return OwnedBooking.find(bookings, userId, bookingId);
  }
}
