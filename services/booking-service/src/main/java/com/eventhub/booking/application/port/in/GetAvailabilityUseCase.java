package com.eventhub.booking.application.port.in;

import java.util.UUID;

public interface GetAvailabilityUseCase {

  /** Throws NotFoundException for an unknown event and for one known only as cancelled. */
  EventAvailability get(UUID eventId);
}
