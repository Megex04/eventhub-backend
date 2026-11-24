package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.Event;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CreateEventUseCase {

  Event create(Command command);

  /** {@code organizerId} comes from the authenticated user, never from the request body. */
  record Command(
      UUID organizerId,
      String title,
      String description,
      String venue,
      Instant startsAt,
      Instant endsAt,
      List<ZoneSpec> zones) {}
}
