package com.eventhub.catalog.application.port.in;

import java.util.UUID;

public interface DeleteEventUseCase {

  void delete(UUID eventId, UUID requesterId);
}
