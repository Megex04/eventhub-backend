package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.RemoveZoneUseCase;
import com.eventhub.catalog.application.port.in.UpdateZoneUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.port.out.IdGenerator;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.TicketZone;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Zone management of a draft event. Zone changes emit no domain events. */
@Service
@Transactional
public class ZoneService implements AddZoneUseCase, UpdateZoneUseCase, RemoveZoneUseCase {

  private final EventRepository repository;
  private final IdGenerator ids;
  private final Clock clock;
  private final EventLookup lookup;

  public ZoneService(EventRepository repository, IdGenerator ids, Clock clock) {
    this.repository = repository;
    this.ids = ids;
    this.clock = clock;
    this.lookup = new EventLookup(repository);
  }

  @Override
  public TicketZone addZone(AddZoneUseCase.Command command) {
    Event event = lookup.ownedBy(command.eventId(), command.requesterId());
    ZoneSpec spec = command.zone();
    TicketZone zone = TicketZone.create(ids.newId(), spec.name(), spec.price(), spec.capacity());
    event.addZone(zone, clock);
    repository.save(event);
    return zone;
  }

  @Override
  public TicketZone updateZone(UpdateZoneUseCase.Command command) {
    Event event = lookup.ownedBy(command.eventId(), command.requesterId());
    ZoneSpec spec = command.zone();
    TicketZone zone =
        event.updateZone(command.zoneId(), spec.name(), spec.price(), spec.capacity(), clock);
    repository.save(event);
    return zone;
  }

  @Override
  public void removeZone(UUID eventId, UUID zoneId, UUID requesterId) {
    Event event = lookup.ownedBy(eventId, requesterId);
    event.removeZone(zoneId, clock);
    repository.save(event);
  }
}
