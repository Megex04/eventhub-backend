package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.port.in.CancelEventUseCase;
import com.eventhub.catalog.application.port.in.CreateEventUseCase;
import com.eventhub.catalog.application.port.in.DeleteEventUseCase;
import com.eventhub.catalog.application.port.in.PublishEventUseCase;
import com.eventhub.catalog.application.port.in.RescheduleEventUseCase;
import com.eventhub.catalog.application.port.in.UpdateEventUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.port.out.IdGenerator;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.TicketZone;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event lifecycle commands. Each one saves the aggregate and hands its domain events to the
 * publisher inside the same transaction, so nothing is emitted when the change is rejected.
 */
@Service
@Transactional
public class EventCommandService
    implements CreateEventUseCase,
        UpdateEventUseCase,
        DeleteEventUseCase,
        PublishEventUseCase,
        CancelEventUseCase,
        RescheduleEventUseCase {

  private final EventRepository repository;
  private final DomainEventPublisher publisher;
  private final IdGenerator ids;
  private final Clock clock;
  private final EventLookup lookup;

  public EventCommandService(
      EventRepository repository, DomainEventPublisher publisher, IdGenerator ids, Clock clock) {
    this.repository = repository;
    this.publisher = publisher;
    this.ids = ids;
    this.clock = clock;
    this.lookup = new EventLookup(repository);
  }

  @Override
  public Event create(CreateEventUseCase.Command command) {
    Event event =
        Event.create(
            ids.newId(),
            command.organizerId(),
            command.title(),
            command.description(),
            command.venue(),
            command.startsAt(),
            command.endsAt(),
            toZones(command.zones()),
            clock);
    return persist(event);
  }

  @Override
  public Event update(UpdateEventUseCase.Command command) {
    Event event = lookup.ownedBy(command.eventId(), command.requesterId());
    event.update(
        command.title(),
        command.description(),
        command.venue(),
        command.startsAt(),
        command.endsAt(),
        clock);
    return persist(event);
  }

  @Override
  public void delete(UUID eventId, UUID requesterId) {
    Event event = lookup.ownedBy(eventId, requesterId);
    event.ensureDeletable();
    repository.delete(event);
  }

  @Override
  public Event publish(UUID eventId, UUID requesterId) {
    Event event = lookup.ownedBy(eventId, requesterId);
    event.publish(clock);
    return persist(event);
  }

  @Override
  public Event cancel(CancelEventUseCase.Command command) {
    Event event = lookup.ownedBy(command.eventId(), command.requesterId());
    event.cancel(command.reason(), clock);
    return persist(event);
  }

  @Override
  public Event reschedule(RescheduleEventUseCase.Command command) {
    Event event = lookup.ownedBy(command.eventId(), command.requesterId());
    command
        .venue()
        .ifPresentOrElse(
            venue -> event.reschedule(command.startsAt(), command.endsAt(), venue, clock),
            () -> event.reschedule(command.startsAt(), command.endsAt(), clock));
    return persist(event);
  }

  private List<TicketZone> toZones(List<ZoneSpec> specs) {
    return specs.stream()
        .map(spec -> TicketZone.create(ids.newId(), spec.name(), spec.price(), spec.capacity()))
        .toList();
  }

  private Event persist(Event event) {
    repository.save(event);
    publisher.publish(event.pullDomainEvents());
    return event;
  }
}
