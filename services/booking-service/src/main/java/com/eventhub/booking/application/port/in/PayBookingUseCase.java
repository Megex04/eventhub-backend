package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.Booking;

public interface PayBookingUseCase {

  /** Returns the CONFIRMED booking; every other outcome is an exception. */
  Booking pay(PayBookingCommand command);
}
