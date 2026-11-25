package com.eventhub.booking.application.port.in;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.domain.Booking;

public interface ListMyBookingsUseCase {

  Page<Booking> list(ListBookingsQuery query);
}
