package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.Booking;
import java.util.UUID;

public interface CancelBookingUseCase {

  Booking cancel(UUID userId, UUID bookingId);
}
