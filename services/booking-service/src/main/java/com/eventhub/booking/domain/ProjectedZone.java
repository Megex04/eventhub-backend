package com.eventhub.booking.domain;

import java.util.UUID;

/** A zone as the catalog published it: the booking side prices bookings from this copy. */
public record ProjectedZone(UUID zoneId, String name, Money price, int capacity) {

  public ProjectedZone {
    Validation.requiredId("zoneId", zoneId);
    Validation.required("name", name);
    Validation.required("price", price);
    Validation.atLeast("capacity", capacity, 1);
  }
}
