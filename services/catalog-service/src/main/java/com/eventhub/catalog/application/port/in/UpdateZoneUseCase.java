package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.TicketZone;
import java.util.UUID;

public interface UpdateZoneUseCase {

  TicketZone updateZone(Command command);

  record Command(UUID eventId, UUID zoneId, UUID requesterId, ZoneSpec zone) {}
}
