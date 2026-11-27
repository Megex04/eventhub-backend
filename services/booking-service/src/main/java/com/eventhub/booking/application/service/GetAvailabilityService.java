package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.EventAvailability;
import com.eventhub.booking.application.port.in.EventAvailability.ZoneAvailability;
import com.eventhub.booking.application.port.in.GetAvailabilityUseCase;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.ProjectedZone;
import com.eventhub.booking.domain.ZoneInventory;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GetAvailabilityService implements GetAvailabilityUseCase {

  private final EventProjectionRepository projections;
  private final InventoryRepository inventory;
  private final Clock clock;

  public GetAvailabilityService(
      EventProjectionRepository projections, InventoryRepository inventory, Clock clock) {
    this.projections = projections;
    this.inventory = inventory;
    this.clock = clock;
  }

  @Override
  public EventAvailability get(UUID eventId) {
    EventProjection event =
        projections
            .findById(eventId)
            .filter(projection -> !projection.isTombstone())
            .orElseThrow(() -> new NotFoundException("Event", eventId));
    Map<UUID, ZoneInventory> inventories =
        inventory.findByEvent(eventId).stream()
            .collect(Collectors.toMap(ZoneInventory::zoneId, Function.identity()));
    return new EventAvailability(
        eventId,
        event.isOnSale(clock),
        event.startsAt().orElseThrow(),
        event.zones().stream().map(zone -> availabilityOf(zone, inventories)).toList());
  }

  private static ZoneAvailability availabilityOf(
      ProjectedZone zone, Map<UUID, ZoneInventory> inventories) {
    ZoneInventory zoneInventory = inventories.get(zone.zoneId());
    int available = zoneInventory == null ? 0 : Math.max(0, zoneInventory.available());
    return new ZoneAvailability(
        zone.zoneId(), zone.name(), zone.price(), zone.capacity(), available);
  }
}
