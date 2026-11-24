package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.Event;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RescheduleEventUseCase {

  Event reschedule(Command command);

  /** An empty {@code venue} keeps the current one, as the contract states. */
  record Command(
      UUID eventId, UUID requesterId, Instant startsAt, Instant endsAt, Optional<String> venue) {}
}
