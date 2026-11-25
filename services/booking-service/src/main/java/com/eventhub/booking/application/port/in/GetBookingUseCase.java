package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.Booking;
import java.util.UUID;

public interface GetBookingUseCase {

  Booking get(UUID userId, UUID bookingId);
}
