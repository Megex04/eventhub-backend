package com.eventhub.catalog.infrastructure.adapter.in.web;

import com.eventhub.catalog.application.port.in.CreateEventUseCase;
import com.eventhub.catalog.application.port.in.DeleteEventUseCase;
import com.eventhub.catalog.application.port.in.GetEventUseCase;
import com.eventhub.catalog.application.port.in.ListEventsUseCase;
import com.eventhub.catalog.application.port.in.UpdateEventUseCase;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.infrastructure.adapter.in.web.api.EventsApi;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.CreateEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventDetailDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventPageDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventStatusDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.UpdateEventRequestDto;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
class EventsController implements EventsApi {

  private final CreateEventUseCase createEvent;
  private final GetEventUseCase getEvent;
  private final ListEventsUseCase listEvents;
  private final UpdateEventUseCase updateEvent;
  private final DeleteEventUseCase deleteEvent;

  EventsController(
      CreateEventUseCase createEvent,
      GetEventUseCase getEvent,
      ListEventsUseCase listEvents,
      UpdateEventUseCase updateEvent,
      DeleteEventUseCase deleteEvent) {
    this.createEvent = createEvent;
    this.getEvent = getEvent;
    this.listEvents = listEvents;
    this.updateEvent = updateEvent;
    this.deleteEvent = deleteEvent;
  }

  @Override
  public ResponseEntity<EventDetailDto> createEvent(CreateEventRequestDto body) {
    Event event = createEvent.create(EventWebMapper.toCommand(body, CurrentUser.requireId()));
    return ResponseEntity.created(locationOf(event)).body(EventWebMapper.toDto(event));
  }

  @Override
  public ResponseEntity<EventDetailDto> getEvent(UUID eventId) {
    return ResponseEntity.ok(EventWebMapper.toDto(getEvent.get(eventId, CurrentUser.viewer())));
  }

  @Override
  public ResponseEntity<EventPageDto> listEvents(
      EventStatusDto status,
      String q,
      Instant from,
      Instant to,
      Boolean mine,
      Integer page,
      Integer size,
      String sort) {
    var query =
        EventWebMapper.toQuery(
            CurrentUser.viewer(), status, q, from, to, Boolean.TRUE.equals(mine), page, size, sort);
    return ResponseEntity.ok(EventWebMapper.toDto(listEvents.list(query)));
  }

  @Override
  public ResponseEntity<EventDetailDto> updateEvent(UUID eventId, UpdateEventRequestDto body) {
    Event event =
        updateEvent.update(EventWebMapper.toCommand(eventId, CurrentUser.requireId(), body));
    return ResponseEntity.ok(EventWebMapper.toDto(event));
  }

  @Override
  public ResponseEntity<Void> deleteEvent(UUID eventId) {
    deleteEvent.delete(eventId, CurrentUser.requireId());
    return ResponseEntity.noContent().build();
  }

  private static URI locationOf(Event event) {
    return UriComponentsBuilder.fromPath(EventsApi.PATH_GET_EVENT)
        .buildAndExpand(event.id())
        .toUri();
  }
}
