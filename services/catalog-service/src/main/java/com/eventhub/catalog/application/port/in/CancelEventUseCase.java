package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.Event;
import java.util.UUID;

public interface CancelEventUseCase {

  Event cancel(Command command);

  record Command(UUID eventId, UUID requesterId, String reason) {}
}
