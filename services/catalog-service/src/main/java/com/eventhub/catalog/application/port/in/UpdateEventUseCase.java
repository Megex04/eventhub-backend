package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.Event;
import java.time.Instant;
import java.util.UUID;

public interface UpdateEventUseCase {

  Event update(Command command);

  record Command(
      UUID eventId,
      UUID requesterId,
      String title,
      String description,
      String venue,
      Instant startsAt,
      Instant endsAt) {}
}
