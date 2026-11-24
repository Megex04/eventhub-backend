package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.port.in.CreateEventUseCase;
import com.eventhub.catalog.application.port.in.ListEventsUseCase;
import com.eventhub.catalog.application.port.in.RescheduleEventUseCase;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.InvalidValueException;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.CreateEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventDetailDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventPageDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventStatusDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.MoneyDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.RescheduleEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.ZoneRequestDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventWebMapperTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Instant STARTS_AT = NOW.plus(Duration.ofDays(30));
  private static final Instant ENDS_AT = STARTS_AT.plus(Duration.ofHours(4));
  private static final UUID ORGANIZER = UUID.fromString("3f1c2a54-8b7e-4d1a-9c52-6a0f4b7d1e01");

  @Test
  void should_take_the_organizer_from_the_argument_when_mapping_a_create_request() {
    CreateEventRequestDto body =
        new CreateEventRequestDto("Rock Fest", "Estadio Nacional", STARTS_AT, ENDS_AT)
            .description("Open air")
            .zones(List.of(new ZoneRequestDto("VIP", new MoneyDto("150.00", "PEN"), 50)));

    CreateEventUseCase.Command command = EventWebMapper.toCommand(body, ORGANIZER);

    assertThat(command.organizerId()).isEqualTo(ORGANIZER);
    assertThat(command.title()).isEqualTo("Rock Fest");
    assertThat(command.description()).isEqualTo("Open air");
    assertThat(command.venue()).isEqualTo("Estadio Nacional");
    assertThat(command.startsAt()).isEqualTo(STARTS_AT);
    assertThat(command.endsAt()).isEqualTo(ENDS_AT);
    assertThat(command.zones())
        .singleElement()
        .satisfies(zone -> {
          assertThat(zone.name()).isEqualTo("VIP");
          assertThat(zone.price()).isEqualTo(Money.of("150.00", "PEN"));
          assertThat(zone.capacity()).isEqualTo(50);
        });
  }

  @Test
  void should_map_to_no_zones_when_the_create_request_omits_them() {
    CreateEventRequestDto body =
        new CreateEventRequestDto("Rock Fest", "Estadio Nacional", STARTS_AT, ENDS_AT);

    assertThat(EventWebMapper.toCommand(body, ORGANIZER).zones()).isEmpty();
  }

  @Test
  void should_reject_the_amount_when_it_is_not_a_decimal() {
    ZoneRequestDto zone = new ZoneRequestDto("VIP", new MoneyDto("abc", "PEN"), 50);

    assertThatThrownBy(() -> EventWebMapper.toZoneSpec(zone))
        .isInstanceOf(InvalidValueException.class)
        .extracting("field")
        .isEqualTo("amount");
  }

  @Test
  void should_keep_the_current_venue_when_reschedule_omits_it() {
    UUID eventId = UUID.randomUUID();
    RescheduleEventRequestDto body = new RescheduleEventRequestDto(STARTS_AT, ENDS_AT);

    RescheduleEventUseCase.Command command = EventWebMapper.toCommand(eventId, ORGANIZER, body);

    assertThat(command.venue()).isEmpty();
    assertThat(command.eventId()).isEqualTo(eventId);
    assertThat(command.requesterId()).isEqualTo(ORGANIZER);
  }

  @Test
  void should_carry_the_new_venue_when_reschedule_provides_it() {
    RescheduleEventRequestDto body =
        new RescheduleEventRequestDto(STARTS_AT, ENDS_AT).venue("Arena Lima");

    assertThat(EventWebMapper.toCommand(UUID.randomUUID(), ORGANIZER, body).venue())
        .isEqualTo(Optional.of("Arena Lima"));
  }

  @Test
  void should_render_money_as_two_decimal_text_when_mapping_an_event() {
    Event event = draftWithZone("1500");

    EventDetailDto dto = EventWebMapper.toDto(event);

    assertThat(dto.getZones())
        .singleElement()
        .satisfies(zone -> {
          assertThat(zone.getPrice().getAmount()).isEqualTo("1500.00");
          assertThat(zone.getPrice().getCurrency()).isEqualTo("PEN");
        });
  }

  @Test
  void should_expose_the_event_fields_when_mapping_a_draft() {
    Event event = draftWithZone("10.00");

    EventDetailDto dto = EventWebMapper.toDto(event);

    assertThat(dto.getId()).isEqualTo(event.id());
    assertThat(dto.getOrganizerId()).isEqualTo(ORGANIZER);
    assertThat(dto.getStatus()).isEqualTo(EventStatusDto.DRAFT);
    assertThat(dto.getStartsAt()).isEqualTo(STARTS_AT);
    assertThat(dto.getCreatedAt()).isEqualTo(NOW);
    assertThat(dto.getCancelReason()).isNull();
  }

  @Test
  void should_omit_the_description_when_the_event_has_none() {
    Event event =
        Event.create(
            UUID.randomUUID(),
            ORGANIZER,
            "Rock Fest",
            null,
            "Estadio",
            STARTS_AT,
            ENDS_AT,
            List.of(),
            CLOCK);

    assertThat(EventWebMapper.toDto(event).getDescription()).isNull();
  }

  @Test
  void should_expose_the_cancel_reason_when_the_event_is_cancelled() {
    Event event = draftWithZone("10.00");
    event.publish(CLOCK);
    event.cancel("Venue unavailable", CLOCK);

    EventDetailDto dto = EventWebMapper.toDto(event);

    assertThat(dto.getStatus()).isEqualTo(EventStatusDto.CANCELLED);
    assertThat(dto.getCancelReason()).isEqualTo("Venue unavailable");
  }

  @ParameterizedTest
  @CsvSource({
    "startsAt, STARTS_AT_ASC",
    "-startsAt, STARTS_AT_DESC",
    "title, TITLE_ASC",
    "-title, TITLE_DESC"
  })
  void should_translate_the_sort_parameter_when_it_is_a_contract_value(
      String raw, EventSort expected) {
    assertThat(EventWebMapper.toSort(raw)).isEqualTo(expected);
  }

  @Test
  void should_reject_the_sort_when_it_is_not_in_the_contract() {
    assertThatThrownBy(() -> EventWebMapper.toSort("price"))
        .isInstanceOf(InvalidValueException.class)
        .extracting("field")
        .isEqualTo("sort");
  }

  @Test
  void should_apply_every_filter_when_building_the_list_query() {
    Viewer viewer = Viewer.authenticated(ORGANIZER);

    ListEventsUseCase.Query query =
        EventWebMapper.toQuery(
            viewer, EventStatusDto.DRAFT, " rock ", NOW, STARTS_AT, true, 2, 50, "-title");

    assertThat(query.criteria().viewer()).isEqualTo(viewer);
    assertThat(query.criteria().status()).contains(EventStatus.DRAFT);
    assertThat(query.criteria().text()).contains("rock");
    assertThat(query.criteria().from()).contains(NOW);
    assertThat(query.criteria().to()).contains(STARTS_AT);
    assertThat(query.criteria().onlyMine()).isTrue();
    assertThat(query.page().page()).isEqualTo(2);
    assertThat(query.page().size()).isEqualTo(50);
    assertThat(query.sort()).isEqualTo(EventSort.TITLE_DESC);
  }

  @Test
  void should_leave_filters_empty_when_the_list_query_has_no_parameters() {
    ListEventsUseCase.Query query =
        EventWebMapper.toQuery(
            Viewer.anonymous(), null, null, null, null, false, 0, 20, "startsAt");

    assertThat(query.criteria().status()).isEmpty();
    assertThat(query.criteria().text()).isEmpty();
    assertThat(query.criteria().from()).isEmpty();
    assertThat(query.criteria().to()).isEmpty();
    assertThat(query.criteria().onlyMine()).isFalse();
  }

  @Test
  void should_report_page_totals_when_mapping_a_result() {
    EventSummary summary =
        new EventSummary(
            UUID.randomUUID(), "Rock Fest", "Estadio", STARTS_AT, ENDS_AT, EventStatus.PUBLISHED);
    PageResult<EventSummary> result = new PageResult<>(List.of(summary), 1, 10, 11);

    EventPageDto dto = EventWebMapper.toDto(result);

    assertThat(dto.getContent())
        .singleElement()
        .satisfies(item -> {
          assertThat(item.getId()).isEqualTo(summary.id());
          assertThat(item.getStatus()).isEqualTo(EventStatusDto.PUBLISHED);
        });
    assertThat(dto.getPage()).isEqualTo(1);
    assertThat(dto.getSize()).isEqualTo(10);
    assertThat(dto.getTotalElements()).isEqualTo(11L);
    assertThat(dto.getTotalPages()).isEqualTo(2);
  }

  private static Event draftWithZone(String price) {
    TicketZone zone = TicketZone.create(UUID.randomUUID(), "General", Money.of(price, "PEN"), 100);
    return Event.create(
        UUID.randomUUID(),
        ORGANIZER,
        "Rock Fest",
        "Open air",
        "Estadio Nacional",
        STARTS_AT,
        ENDS_AT,
        List.of(zone),
        CLOCK);
  }
}
