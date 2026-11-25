package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventAvailability(
    UUID eventId, boolean onSale, Instant startsAt, List<ZoneAvailability> zones) {

  /** {@code available} is capacity minus what is held or sold, never negative. */
  public record ZoneAvailability(
      UUID zoneId, String name, Money price, int capacity, int available) {}
}
