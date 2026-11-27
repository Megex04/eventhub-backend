package com.eventhub.booking.domain;

import java.util.UUID;

/** A priced line of a booking. The zone name and price are frozen when the booking is created. */
public record BookingItem(UUID zoneId, String zoneName, int quantity, Money unitPrice) {

  public BookingItem {
    Validation.requiredId("zoneId", zoneId);
    Validation.required("zoneName", zoneName);
    Validation.atLeast("quantity", quantity, 1);
    Validation.required("unitPrice", unitPrice);
  }

  public Money subtotal() {
    return unitPrice.times(quantity);
  }
}
