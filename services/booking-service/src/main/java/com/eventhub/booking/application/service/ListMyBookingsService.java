package com.eventhub.booking.application.service;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.port.in.ListBookingsQuery;
import com.eventhub.booking.application.port.in.ListMyBookingsUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.domain.Booking;

public class ListMyBookingsService implements ListMyBookingsUseCase {

  private final BookingRepository bookings;

  public ListMyBookingsService(BookingRepository bookings) {
    this.bookings = bookings;
  }

  @Override
  public Page<Booking> list(ListBookingsQuery query) {
    return bookings.findByUser(query.userId(), query.status(), query.page());
  }
}
