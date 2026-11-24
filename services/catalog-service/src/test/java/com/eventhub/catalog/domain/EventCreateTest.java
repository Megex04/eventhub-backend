package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.ORGANIZER_ID;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventCreateTest {

  private static Event create(
      String title, String description, String venue, Instant startsAt, Instant endsAt) {
    return Event.create(
        UUID.randomUUID(),
        ORGANIZER_ID,
        title,
        description,
        venue,
        startsAt,
        endsAt,
        List.of(),
        CLOCK);
  }

  @Test
  void should_create_draft_event_when_data_is_valid() {
    UUID id = UUID.randomUUID();

    Event event =
        Event.create(
            id,
            ORGANIZER_ID,
            "Rock Fest",
            "Open air",
            "Estadio Nacional",
            STARTS_AT,
            ENDS_AT,
            List.of(),
            CLOCK);

    assertThat(event.id()).isEqualTo(id);
    assertThat(event.organizerId()).isEqualTo(ORGANIZER_ID);
    assertThat(event.title()).isEqualTo("Rock Fest");
    assertThat(event.description()).isEqualTo("Open air");
    assertThat(event.venue()).isEqualTo("Estadio Nacional");
    assertThat(event.startsAt()).isEqualTo(STARTS_AT);
    assertThat(event.endsAt()).isEqualTo(ENDS_AT);
    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(event.zones()).isEmpty();
    assertThat(event.cancelReason()).isEmpty();
    assertThat(event.createdAt()).isEqualTo(NOW);
    assertThat(event.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void should_not_register_domain_events_when_event_is_created() {
    assertThat(EventFixtures.draftEvent().pullDomainEvents()).isEmpty();
  }

  @Test
  void should_create_event_with_initial_zones_when_zones_are_provided() {
    Event event =
        Event.create(
            UUID.randomUUID(),
            ORGANIZER_ID,
            "Rock Fest",
            "",
            "Estadio Nacional",
            STARTS_AT,
            ENDS_AT,
            List.of(zone("General"), zone("VIP")),
            CLOCK);

    assertThat(event.zones()).extracting(TicketZone::name).containsExactly("General", "VIP");
  }

  @Test
  void should_use_empty_description_when_description_is_null() {
    assertThat(create("Rock Fest", null, "Estadio", STARTS_AT, ENDS_AT).description()).isEmpty();
  }

  @Test
  void should_strip_text_fields_when_they_have_surrounding_whitespace() {
    Event event = create("  Rock Fest ", " desc ", "  Estadio ", STARTS_AT, ENDS_AT);

    assertThat(event.title()).isEqualTo("Rock Fest");
    assertThat(event.description()).isEqualTo("desc");
    assertThat(event.venue()).isEqualTo("Estadio");
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 120})
  void should_accept_title_when_length_is_at_the_bounds(int length) {
    assertThat(create("t".repeat(length), "", "Estadio", STARTS_AT, ENDS_AT).title())
        .hasSize(length);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 121})
  void should_reject_title_when_length_is_out_of_bounds(int length) {
    assertThatThrownBy(() -> create("t".repeat(length), "", "Estadio", STARTS_AT, ENDS_AT))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("title"));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 160})
  void should_accept_venue_when_length_is_at_the_bounds(int length) {
    assertThat(create("Rock Fest", "", "v".repeat(length), STARTS_AT, ENDS_AT).venue())
        .hasSize(length);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 161})
  void should_reject_venue_when_length_is_out_of_bounds(int length) {
    assertThatThrownBy(() -> create("Rock Fest", "", "v".repeat(length), STARTS_AT, ENDS_AT))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("venue"));
  }

  @Test
  void should_accept_description_when_it_has_maximum_length() {
    assertThat(create("Rock Fest", "d".repeat(4000), "Estadio", STARTS_AT, ENDS_AT).description())
        .hasSize(4000);
  }

  @Test
  void should_reject_description_when_it_exceeds_maximum_length() {
    assertThatThrownBy(() -> create("Rock Fest", "d".repeat(4001), "Estadio", STARTS_AT, ENDS_AT))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("description"));
  }

  @Test
  void should_reject_event_when_ends_at_equals_starts_at() {
    assertThatThrownBy(() -> create("Rock Fest", "", "Estadio", STARTS_AT, STARTS_AT))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("endsAt"));
  }

  @Test
  void should_reject_event_when_ends_at_is_before_starts_at() {
    assertThatThrownBy(() -> create("Rock Fest", "", "Estadio", STARTS_AT, STARTS_AT.minusNanos(1)))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_accept_event_when_ends_at_is_one_nanosecond_after_starts_at() {
    assertThat(create("Rock Fest", "", "Estadio", STARTS_AT, STARTS_AT.plusNanos(1)).endsAt())
        .isEqualTo(STARTS_AT.plusNanos(1));
  }

  @Test
  void should_accept_event_when_starts_at_is_in_the_past() {
    Event event = create("Rock Fest", "", "Estadio", NOW.minusSeconds(60), NOW.plusSeconds(60));

    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
  }

  @Test
  void should_reject_event_when_dates_are_missing() {
    assertThatThrownBy(() -> create("Rock Fest", "", "Estadio", null, ENDS_AT))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("startsAt"));
    assertThatThrownBy(() -> create("Rock Fest", "", "Estadio", STARTS_AT, null))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("endsAt"));
  }

  @Test
  void should_reject_event_when_ids_are_missing() {
    assertThatThrownBy(() ->
            Event.create(
                null,
                ORGANIZER_ID,
                "Rock Fest",
                "",
                "Estadio",
                STARTS_AT,
                ENDS_AT,
                List.of(),
                CLOCK))
        .isInstanceOf(InvalidValueException.class);
    assertThatThrownBy(() ->
            Event.create(
                UUID.randomUUID(),
                null,
                "Rock Fest",
                "",
                "Estadio",
                STARTS_AT,
                ENDS_AT,
                List.of(),
                CLOCK))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_event_when_it_has_more_than_twenty_zones() {
    List<TicketZone> zones = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      zones.add(zone("Zone " + i));
    }

    assertThatThrownBy(() ->
            Event.create(
                UUID.randomUUID(),
                ORGANIZER_ID,
                "Rock Fest",
                "",
                "Estadio",
                STARTS_AT,
                ENDS_AT,
                zones,
                CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.ZONE_LIMIT_EXCEEDED));
  }

  @Test
  void should_reject_event_when_initial_zones_repeat_a_name_ignoring_case() {
    assertThatThrownBy(() ->
            Event.create(
                UUID.randomUUID(),
                ORGANIZER_ID,
                "Rock Fest",
                "",
                "Estadio",
                STARTS_AT,
                ENDS_AT,
                List.of(zone("VIP"), zone("vip")),
                CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.DUPLICATE_ZONE_NAME));
  }
}
