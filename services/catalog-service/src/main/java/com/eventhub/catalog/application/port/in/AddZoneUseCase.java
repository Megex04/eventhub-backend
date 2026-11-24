package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.TicketZone;
import java.util.UUID;

public interface AddZoneUseCase {

  TicketZone addZone(Command command);

  record Command(UUID eventId, UUID requesterId, ZoneSpec zone) {}
}
