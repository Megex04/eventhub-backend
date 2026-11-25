package com.eventhub.booking.domain;

import java.util.UUID;

/** A zone does not have enough free tickets for the request (maps to HTTP 409). */
public final class InsufficientInventoryException extends DomainException {

  private final UUID zoneId;
  private final int requested;
  private final int available;

  public InsufficientInventoryException(UUID zoneId, int requested, int available) {
    super("Zone %s has %d tickets available, %d requested".formatted(zoneId, available, requested));
    this.zoneId = zoneId;
    this.requested = requested;
    this.available = available;
  }

  public UUID zoneId() {
    return zoneId;
  }

  public int requested() {
    return requested;
  }

  public int available() {
    return available;
  }
}
