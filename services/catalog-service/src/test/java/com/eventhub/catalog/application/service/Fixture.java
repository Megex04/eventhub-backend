package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.port.out.InMemoryEventRepository;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wires the services over in-memory fakes and offers helpers to arrange stored events. */
final class Fixture {

  static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  static final Instant STARTS_AT = NOW.plus(Duration.ofDays(30));
  static final Instant ENDS_AT = STARTS_AT.plus(Duration.ofHours(4));
  static final UUID OWNER = UUID.fromString("3f1c2a54-8b7e-4d1a-9c52-6a0f4b7d1e01");
  static final UUID STRANGER = UUID.fromString("9a7d5e12-3c4b-4f60-8e21-b5d0c3a96f02");

  final InMemoryEventRepository repository = new InMemoryEventRepository();
  // The fake hands out copies, so tests re-read through reload() to observe persisted state.
  final RecordingDomainEventPublisher publisher = new RecordingDomainEventPublisher();
  final SequentialIdGenerator ids = new SequentialIdGenerator();
  final MutableClock clock = new MutableClock(NOW);

  final EventCommandService commands = new EventCommandService(repository, publisher, ids, clock);
  final ZoneService zones = new ZoneService(repository, ids, clock);
  final EventQueryService queries = new EventQueryService(repository);

  static Money money(String amount) {
    return Money.of(amount, "PEN");
  }

  static ZoneSpec zoneSpec(String name) {
    return new ZoneSpec(name, money("100.00"), 100);
  }

  Event givenDraft(UUID organizerId) {
    Event event =
        Event.create(
            ids.newId(),
            organizerId,
            "Rock Fest",
            "Open air",
            "Estadio Nacional",
            STARTS_AT,
            ENDS_AT,
            List.of(TicketZone.create(ids.newId(), "General", money("100.00"), 100)),
            clock);
    repository.save(event);
    return reload(event);
  }

  Event givenPublished(UUID organizerId) {
    Event event = givenDraft(organizerId);
    event.publish(clock);
    event.pullDomainEvents();
    repository.save(event);
    return reload(event);
  }

  Event givenCancelled(UUID organizerId) {
    Event event = givenPublished(organizerId);
    event.cancel("Venue unavailable", clock);
    event.pullDomainEvents();
    repository.save(event);
    return reload(event);
  }

  /** The persisted state of the event, as the next transaction would see it. */
  Event reload(Event event) {
    return repository.findById(event.id()).orElseThrow();
  }
}
