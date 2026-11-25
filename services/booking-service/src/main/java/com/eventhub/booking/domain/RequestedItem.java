package com.eventhub.booking.domain;

import java.util.UUID;

/** What the customer asked for: a zone and how many tickets. Prices come from the projection. */
public record RequestedItem(UUID zoneId, int quantity) {

  public RequestedItem {
    Validation.requiredId("zoneId", zoneId);
    Validation.atLeast("quantity", quantity, 1);
  }
}
