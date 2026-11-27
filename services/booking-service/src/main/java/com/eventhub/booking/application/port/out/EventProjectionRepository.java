package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.EventProjection;
import java.util.Optional;
import java.util.UUID;

public interface EventProjectionRepository {

  Optional<EventProjection> findById(UUID eventId);

  /** Inserts or replaces the projection of the event. */
  void save(EventProjection projection);
}
