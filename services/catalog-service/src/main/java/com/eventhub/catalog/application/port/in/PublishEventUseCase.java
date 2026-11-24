package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.Event;
import java.util.UUID;

public interface PublishEventUseCase {

  Event publish(UUID eventId, UUID requesterId);
}
