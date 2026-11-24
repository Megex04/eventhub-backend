package com.eventhub.catalog.application.port.in;

import java.util.UUID;

public interface RemoveZoneUseCase {

  void removeZone(UUID eventId, UUID zoneId, UUID requesterId);
}
