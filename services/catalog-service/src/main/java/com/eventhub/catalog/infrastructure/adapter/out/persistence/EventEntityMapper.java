package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Explicit translation between the Event aggregate and its JPA entities. */
final class EventEntityMapper {

  private EventEntityMapper() {}

  static Event toDomain(EventEntity entity) {
    List<TicketZone> zones = entity.getZones().stream().map(EventEntityMapper::toDomain).toList();
    return Event.restore(
        entity.getId(),
        entity.getOrganizerId(),
        entity.getTitle(),
        entity.getDescription(),
        entity.getVenue(),
        entity.getStartsAt(),
        entity.getEndsAt(),
        entity.getStatus(),
        Optional.ofNullable(entity.getCancelReason()),
        zones,
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }

  static EventEntity toNewEntity(Event event) {
    EventEntity entity = new EventEntity(event.id(), event.organizerId(), event.createdAt());
    copyState(event, entity);
    return entity;
  }

  /** Copies the aggregate's mutable state onto a managed entity and reconciles its zones. */
  static void copyState(Event event, EventEntity entity) {
    entity.applyState(
        event.title(),
        event.description(),
        event.venue(),
        event.startsAt(),
        event.endsAt(),
        event.status(),
        event.cancelReason().orElse(null),
        event.updatedAt());
    syncZones(event.zones(), entity);
  }

  private static TicketZone toDomain(TicketZoneEntity entity) {
    return TicketZone.create(
        entity.getId(),
        entity.getName(),
        Money.of(entity.getPriceAmount().toPlainString(), entity.getPriceCurrency()),
        entity.getCapacity());
  }

  /** Keeps rows of surviving zones (by id), adds new ones and drops the removed ones. */
  private static void syncZones(List<TicketZone> zones, EventEntity entity) {
    Set<UUID> kept = zones.stream().map(TicketZone::id).collect(Collectors.toSet());
    entity.getZones().removeIf(row -> !kept.contains(row.getId()));
    Map<UUID, TicketZoneEntity> rows = new HashMap<>();
    entity.getZones().forEach(row -> rows.put(row.getId(), row));
    for (int position = 0; position < zones.size(); position++) {
      TicketZone zone = zones.get(position);
      TicketZoneEntity row = rows.get(zone.id());
      if (row == null) {
        row = new TicketZoneEntity(zone.id(), entity);
        entity.getZones().add(row);
      }
      row.applyState(
          (short) position,
          zone.name(),
          zone.price().amount(),
          zone.price().currency(),
          zone.capacity());
    }
  }
}
