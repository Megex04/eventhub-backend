package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.cancelledEvent;
import static com.eventhub.catalog.domain.EventFixtures.clockAt;
import static com.eventhub.catalog.domain.EventFixtures.draftEvent;
import static com.eventhub.catalog.domain.EventFixtures.draftEventWithZone;
import static com.eventhub.catalog.domain.EventFixtures.money;
import static com.eventhub.catalog.domain.EventFixtures.publishedEvent;
import static com.eventhub.catalog.domain.EventFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventZonesTest {

  private static final Instant LATER = NOW.plus(Duration.ofHours(1));

  static Stream<Arguments> nonDraftEvents() {
    Supplier<Event> published = EventFixtures::publishedEvent;
    Supplier<Event> cancelled = EventFixtures::cancelledEvent;
    return Stream.of(
        Arguments.of(Named.of("PUBLISHED", published)),
        Arguments.of(Named.of("CANCELLED", cancelled)));
  }

  @Test
  void should_add_zone_and_touch_updated_at_when_event_is_draft() {
    Event event = draftEvent();
    TicketZone zone = zone("General");

    event.addZone(zone, clockAt(LATER));

    assertThat(event.zones()).containsExactly(zone);
    assertThat(event.updatedAt()).isEqualTo(LATER);
  }

  @Test
  void should_keep_zones_in_insertion_order_when_several_are_added() {
    Event event = draftEvent();

    event.addZone(zone("B"), CLOCK);
    event.addZone(zone("A"), CLOCK);

    assertThat(event.zones()).extracting(TicketZone::name).containsExactly("B", "A");
  }

  @Test
  void should_accept_the_twentieth_zone_when_event_has_nineteen() {
    Event event = draftEvent();
    for (int i = 0; i < 19; i++) {
      event.addZone(zone("Zone " + i), CLOCK);
    }

    event.addZone(zone("Zone 19"), CLOCK);

    assertThat(event.zones()).hasSize(20);
  }

  @Test
  void should_reject_the_twenty_first_zone_when_event_has_twenty() {
    Event event = draftEvent();
    for (int i = 0; i < 20; i++) {
      event.addZone(zone("Zone " + i), CLOCK);
    }

    assertThatThrownBy(() -> event.addZone(zone("One more"), CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.ZONE_LIMIT_EXCEEDED));
    assertThat(event.zones()).hasSize(20);
  }

  @Test
  void should_reject_zone_when_name_already_exists_ignoring_case_and_spaces() {
    Event event = draftEvent();
    event.addZone(zone("VIP"), CLOCK);

    assertThatThrownBy(() -> event.addZone(zone("vip"), CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.DUPLICATE_ZONE_NAME));
  }

  @Test
  void should_not_expose_a_mutable_zone_list_when_zones_are_read() {
    Event event = draftEventWithZone();

    assertThatThrownBy(() -> event.zones().add(zone("Hack")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void should_update_zone_keeping_its_id_when_event_is_draft() {
    Event event = draftEventWithZone();
    UUID zoneId = event.zones().getFirst().id();

    TicketZone updated = event.updateZone(zoneId, "Platinum", money("500.00"), 10, clockAt(LATER));

    assertThat(updated.id()).isEqualTo(zoneId);
    assertThat(event.zones()).containsExactly(updated);
    assertThat(event.zones().getFirst().name()).isEqualTo("Platinum");
    assertThat(event.zones().getFirst().price()).isEqualTo(money("500.00"));
    assertThat(event.zones().getFirst().capacity()).isEqualTo(10);
    assertThat(event.updatedAt()).isEqualTo(LATER);
  }

  @Test
  void should_allow_zone_update_when_name_is_unchanged() {
    Event event = draftEventWithZone();
    UUID zoneId = event.zones().getFirst().id();

    event.updateZone(zoneId, "general", money("1.00"), 5, CLOCK);

    assertThat(event.zones().getFirst().name()).isEqualTo("general");
  }

  @Test
  void should_reject_zone_update_when_name_belongs_to_another_zone() {
    Event event = draftEventWithZone();
    event.addZone(zone("VIP"), CLOCK);
    UUID generalId = event.zones().getFirst().id();

    assertThatThrownBy(() -> event.updateZone(generalId, "VIP", money("1.00"), 5, CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.DUPLICATE_ZONE_NAME));
    assertThat(event.zones().getFirst().name()).isEqualTo("General");
  }

  @Test
  void should_reject_zone_update_when_zone_does_not_exist() {
    Event event = draftEventWithZone();
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> event.updateZone(unknown, "VIP", money("1.00"), 5, CLOCK))
        .isInstanceOfSatisfying(
            ZoneNotFoundException.class, e -> assertThat(e.zoneId()).isEqualTo(unknown));
  }

  @Test
  void should_keep_zone_unchanged_when_update_values_are_invalid() {
    Event event = draftEventWithZone();
    UUID zoneId = event.zones().getFirst().id();

    assertThatThrownBy(() -> event.updateZone(zoneId, "VIP", money("1.00"), 0, CLOCK))
        .isInstanceOf(InvalidValueException.class);
    assertThat(event.zones().getFirst().capacity()).isEqualTo(100);
  }

  @Test
  void should_remove_zone_when_event_is_draft() {
    Event event = draftEventWithZone();
    UUID zoneId = event.zones().getFirst().id();

    event.removeZone(zoneId, clockAt(LATER));

    assertThat(event.zones()).isEmpty();
    assertThat(event.updatedAt()).isEqualTo(LATER);
  }

  @Test
  void should_reject_zone_removal_when_zone_does_not_exist() {
    Event event = draftEventWithZone();

    assertThatThrownBy(() -> event.removeZone(UUID.randomUUID(), CLOCK))
        .isInstanceOf(ZoneNotFoundException.class);
    assertThat(event.zones()).hasSize(1);
  }

  @ParameterizedTest
  @MethodSource("nonDraftEvents")
  void should_reject_zone_changes_when_event_is_not_draft(Supplier<Event> eventSupplier) {
    Event event = eventSupplier.get();
    UUID existingZoneId =
        event.zones().isEmpty() ? UUID.randomUUID() : event.zones().getFirst().id();

    assertThatThrownBy(() -> event.addZone(zone("New"), CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> event.updateZone(existingZoneId, "New", money("1.00"), 1, CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> event.removeZone(existingZoneId, CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThat(event.zones()).hasSize(1);
  }

  @Test
  void should_keep_zones_immutable_when_the_event_is_published() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.addZone(zone("Late"), CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.PUBLISHED));
  }

  @Test
  void should_report_cancelled_status_when_zone_is_added_to_cancelled_event() {
    Event event = cancelledEvent();

    assertThatThrownBy(() -> event.addZone(zone("Late"), CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.CANCELLED));
  }
}
