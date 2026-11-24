package com.eventhub.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventPublished(
    UUID eventId,
    UUID organizerId,
    String title,
    String venue,
    Instant startsAt,
    Instant endsAt,
    List<Zone> zones,
    Instant occurredAt)
    implements DomainEvent {

  public EventPublished {
    zones = List.copyOf(zones);
  }

  /** Snapshot of a zone at publication time; later changes are impossible because zones freeze. */
  public record Zone(UUID zoneId, String name, int capacity, Money price) {}
}
