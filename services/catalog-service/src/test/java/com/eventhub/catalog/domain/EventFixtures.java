package com.eventhub.catalog.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

final class EventFixtures {

  static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final UUID ORGANIZER_ID = UUID.fromString("3f1c2a54-8b7e-4d1a-9c52-6a0f4b7d1e01");
  static final Instant STARTS_AT = NOW.plus(Duration.ofDays(30));
  static final Instant ENDS_AT = STARTS_AT.plus(Duration.ofHours(4));

  private EventFixtures() {}

  static Clock clockAt(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  static Money money(String amount) {
    return Money.of(amount, "PEN");
  }

  static TicketZone zone(String name) {
    return TicketZone.create(UUID.randomUUID(), name, money("100.00"), 100);
  }

  static Event draftEvent() {
    return Event.create(
        UUID.randomUUID(),
        ORGANIZER_ID,
        "Rock Fest",
        "Open air festival",
        "Estadio Nacional",
        STARTS_AT,
        ENDS_AT,
        List.of(),
        CLOCK);
  }

  static Event draftEventWithZone() {
    Event event = draftEvent();
    event.addZone(zone("General"), CLOCK);
    return event;
  }

  static Event publishedEvent() {
    Event event = draftEventWithZone();
    event.publish(CLOCK);
    event.pullDomainEvents();
    return event;
  }

  static Event cancelledEvent() {
    Event event = publishedEvent();
    event.cancel("Venue unavailable", CLOCK);
    event.pullDomainEvents();
    return event;
  }
}
