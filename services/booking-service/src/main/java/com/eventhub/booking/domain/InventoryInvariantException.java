package com.eventhub.booking.domain;

import java.util.UUID;

/**
 * Releasing would leave a zone with a negative {@code reserved}. It signals a bug (a release that
 * was already done), never a user error, so it has no HTTP mapping of its own.
 */
public final class InventoryInvariantException extends DomainException {

  public InventoryInvariantException(UUID zoneId, int reserved, int quantity) {
    super(
        "Cannot release %d tickets from zone %s: only %d are reserved"
            .formatted(quantity, zoneId, reserved));
  }
}
