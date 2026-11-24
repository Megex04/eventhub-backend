package com.eventhub.catalog.infrastructure.adapter.in.web;

import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.CancelEventUseCase;
import com.eventhub.catalog.application.port.in.CreateEventUseCase;
import com.eventhub.catalog.application.port.in.ListEventsUseCase;
import com.eventhub.catalog.application.port.in.RescheduleEventUseCase;
import com.eventhub.catalog.application.port.in.UpdateEventUseCase;
import com.eventhub.catalog.application.port.in.UpdateZoneUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.InvalidValueException;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.CancelEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.CreateEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventDetailDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventPageDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventStatusDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventSummaryDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.MoneyDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.RescheduleEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.TicketZoneDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.UpdateEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.ZoneRequestDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates between the generated DTOs and the application's commands and the domain. It holds no
 * business rules: malformed values surface as the domain's own validation errors.
 */
final class EventWebMapper {

  private EventWebMapper() {}

  // ---- requests -> commands ----

  static CreateEventUseCase.Command toCommand(CreateEventRequestDto body, UUID organizerId) {
    return new CreateEventUseCase.Command(
        organizerId,
        body.getTitle(),
        body.getDescription(),
        body.getVenue(),
        body.getStartsAt(),
        body.getEndsAt(),
        body.getZones().stream().map(EventWebMapper::toZoneSpec).toList());
  }

  static UpdateEventUseCase.Command toCommand(
      UUID eventId, UUID requesterId, UpdateEventRequestDto body) {
    return new UpdateEventUseCase.Command(
        eventId,
        requesterId,
        body.getTitle(),
        body.getDescription(),
        body.getVenue(),
        body.getStartsAt(),
        body.getEndsAt());
  }

  static AddZoneUseCase.Command toAddZoneCommand(
      UUID eventId, UUID requesterId, ZoneRequestDto body) {
    return new AddZoneUseCase.Command(eventId, requesterId, toZoneSpec(body));
  }

  static UpdateZoneUseCase.Command toUpdateZoneCommand(
      UUID eventId, UUID zoneId, UUID requesterId, ZoneRequestDto body) {
    return new UpdateZoneUseCase.Command(eventId, zoneId, requesterId, toZoneSpec(body));
  }

  static CancelEventUseCase.Command toCommand(
      UUID eventId, UUID requesterId, CancelEventRequestDto body) {
    return new CancelEventUseCase.Command(eventId, requesterId, body.getReason());
  }

  static RescheduleEventUseCase.Command toCommand(
      UUID eventId, UUID requesterId, RescheduleEventRequestDto body) {
    return new RescheduleEventUseCase.Command(
        eventId,
        requesterId,
        body.getStartsAt(),
        body.getEndsAt(),
        Optional.ofNullable(body.getVenue()));
  }

  static ZoneSpec toZoneSpec(ZoneRequestDto body) {
    MoneyDto price = body.getPrice();
    return new ZoneSpec(
        body.getName(), Money.of(price.getAmount(), price.getCurrency()), body.getCapacity());
  }

  static ListEventsUseCase.Query toQuery(
      Viewer viewer,
      EventStatusDto status,
      String text,
      Instant from,
      Instant to,
      boolean mine,
      int page,
      int size,
      String sort) {
    EventSearchCriteria criteria = EventSearchCriteria.visibleTo(viewer);
    if (status != null) {
      criteria = criteria.withStatus(EventStatus.valueOf(status.getValue()));
    }
    if (text != null) {
      criteria = criteria.withText(text);
    }
    if (from != null) {
      criteria = criteria.withFrom(from);
    }
    if (to != null) {
      criteria = criteria.withTo(to);
    }
    if (mine) {
      criteria = criteria.mineOnly();
    }
    return new ListEventsUseCase.Query(criteria, toSort(sort), new PageRequest(page, size));
  }

  /** The contract declares {@code sort} as an enum, but it is a plain string once generated. */
  static EventSort toSort(String sort) {
    return switch (sort) {
      case "startsAt" -> EventSort.STARTS_AT_ASC;
      case "-startsAt" -> EventSort.STARTS_AT_DESC;
      case "title" -> EventSort.TITLE_ASC;
      case "-title" -> EventSort.TITLE_DESC;
      default ->
          throw new InvalidValueException(
              "sort", "sort must be one of: startsAt, -startsAt, title, -title");
    };
  }

  // ---- domain -> responses ----

  static EventDetailDto toDto(Event event) {
    return new EventDetailDto(
            event.id(),
            event.organizerId(),
            event.title(),
            event.venue(),
            event.startsAt(),
            event.endsAt(),
            toDto(event.status()),
            event.zones().stream().map(EventWebMapper::toDto).toList(),
            event.createdAt(),
            event.updatedAt())
        .description(event.description().isBlank() ? null : event.description())
        .cancelReason(event.cancelReason().orElse(null));
  }

  static TicketZoneDto toDto(TicketZone zone) {
    return new TicketZoneDto(zone.id(), zone.name(), toDto(zone.price()), zone.capacity());
  }

  static EventPageDto toDto(PageResult<EventSummary> result) {
    List<EventSummaryDto> content = result.content().stream().map(EventWebMapper::toDto).toList();
    return new EventPageDto(
        content, result.page(), result.size(), result.totalElements(), result.totalPages());
  }

  private static EventSummaryDto toDto(EventSummary summary) {
    return new EventSummaryDto(
        summary.id(),
        summary.title(),
        summary.venue(),
        summary.startsAt(),
        summary.endsAt(),
        toDto(summary.status()));
  }

  private static MoneyDto toDto(Money money) {
    return new MoneyDto(money.amount().toPlainString(), money.currency());
  }

  private static EventStatusDto toDto(EventStatus status) {
    return EventStatusDto.fromValue(status.name());
  }
}
