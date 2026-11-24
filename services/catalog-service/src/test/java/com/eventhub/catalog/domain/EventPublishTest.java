package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.ORGANIZER_ID;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.cancelledEvent;
import static com.eventhub.catalog.domain.EventFixtures.clockAt;
import static com.eventhub.catalog.domain.EventFixtures.draftEvent;
import static com.eventhub.catalog.domain.EventFixtures.draftEventWithZone;
import static com.eventhub.catalog.domain.EventFixtures.publishedEvent;
import static com.eventhub.catalog.domain.EventFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventPublishTest {

  private static final Instant LATER = NOW.plus(Duration.ofHours(1));

  @Test
  void should_move_to_published_and_touch_updated_at_when_draft_is_valid() {
    Event event = draftEventWithZone();

    event.publish(clockAt(LATER));

    assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    assertThat(event.updatedAt()).isEqualTo(LATER);
  }

  @Test
  void should_register_event_published_with_zone_snapshot_when_event_is_published() {
    Event event = draftEventWithZone();
    TicketZone zone = event.zones().getFirst();

    event.publish(clockAt(LATER));

    assertThat(event.pullDomainEvents())
        .singleElement()
        .isInstanceOfSatisfying(
            EventPublished.class,
            published -> {
              assertThat(published.eventId()).isEqualTo(event.id());
              assertThat(published.organizerId()).isEqualTo(ORGANIZER_ID);
              assertThat(published.title()).isEqualTo("Rock Fest");
              assertThat(published.venue()).isEqualTo("Estadio Nacional");
              assertThat(published.startsAt()).isEqualTo(STARTS_AT);
              assertThat(published.endsAt()).isEqualTo(ENDS_AT);
              assertThat(published.occurredAt()).isEqualTo(LATER);
              assertThat(published.zones())
                  .containsExactly(
                      new EventPublished.Zone(
                          zone.id(), zone.name(), zone.capacity(), zone.price()));
            });
  }

  @Test
  void should_clear_pending_domain_events_when_they_are_pulled() {
    Event event = draftEventWithZone();
    event.publish(CLOCK);

    assertThat(event.pullDomainEvents()).hasSize(1);
    assertThat(event.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_reject_publish_when_event_has_no_zones() {
    Event event = draftEvent();

    assertThatThrownBy(() -> event.publish(CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.EVENT_WITHOUT_ZONES));
    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(event.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_reject_publish_when_starts_at_equals_now() {
    Event event = eventStartingAt(NOW);

    assertThatThrownBy(() -> event.publish(CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.STARTS_AT_NOT_IN_FUTURE));
    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
  }

  @Test
  void should_reject_publish_when_starts_at_is_in_the_past() {
    Event event = eventStartingAt(NOW.minusSeconds(1));

    assertThatThrownBy(() -> event.publish(CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.STARTS_AT_NOT_IN_FUTURE));
  }

  @Test
  void should_publish_when_starts_at_is_one_nanosecond_in_the_future() {
    Event event = eventStartingAt(NOW.plusNanos(1));

    event.publish(CLOCK);

    assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
  }

  @Test
  void should_reject_publish_when_clock_has_passed_the_start_time_since_creation() {
    Event event = draftEventWithZone();

    assertThatThrownBy(() -> event.publish(clockAt(STARTS_AT)))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.STARTS_AT_NOT_IN_FUTURE));
  }

  @Test
  void should_reject_publish_when_event_is_already_published() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.publish(CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.PUBLISHED));
    assertThat(event.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_reject_publish_when_event_is_cancelled() {
    Event event = cancelledEvent();

    assertThatThrownBy(() -> event.publish(CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.CANCELLED));
  }

  @Test
  void should_report_invalid_state_before_business_rules_when_both_apply() {
    Event event = publishedEvent();

    // A published event has zones and a future start, so force the rule violation via the clock.
    assertThatThrownBy(() -> event.publish(clockAt(STARTS_AT.plusSeconds(1))))
        .isInstanceOf(InvalidStateException.class);
  }

  private static Event eventStartingAt(Instant startsAt) {
    Event event =
        Event.create(
            UUID.randomUUID(),
            ORGANIZER_ID,
            "Rock Fest",
            "",
            "Estadio",
            startsAt,
            startsAt.plus(Duration.ofHours(2)),
            List.of(zone("General")),
            CLOCK);
    return event;
  }
}
