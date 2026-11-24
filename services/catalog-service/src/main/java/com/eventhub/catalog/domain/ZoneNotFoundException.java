package com.eventhub.catalog.domain;

import java.util.UUID;

/** The zone does not belong to the event (maps to HTTP 404). */
public final class ZoneNotFoundException extends DomainException {

  private final UUID zoneId;

  public ZoneNotFoundException(UUID zoneId) {
    super("Zone %s not found in this event".formatted(zoneId));
    this.zoneId = zoneId;
  }

  public UUID zoneId() {
    return zoneId;
  }
}
