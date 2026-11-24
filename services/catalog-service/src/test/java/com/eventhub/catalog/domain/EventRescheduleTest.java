package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.cancelledEvent;
import static com.eventhub.catalog.domain.EventFixtures.clockAt;
import static com.eventhub.catalog.domain.EventFixtures.draftEventWithZone;
import static com.eventhub.catalog.domain.EventFixtures.publishedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventRescheduleTest {

  private static final Instant LATER = NOW.plus(Duration.ofHours(1));
  private static final Instant NEW_START = STARTS_AT.plus(Duration.ofDays(7));
  private static final Instant NEW_END = NEW_START.plus(Duration.ofHours(5));

  @Test
  void should_change_dates_and_keep_venue_when_venue_is_omitted() {
    Event event = publishedEvent();

    event.reschedule(NEW_START, NEW_END, clockAt(LATER));

    assertThat(event.startsAt()).isEqualTo(NEW_START);
    assertThat(event.endsAt()).isEqualTo(NEW_END);
    assertThat(event.venue()).isEqualTo("Estadio Nacional");
    assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    assertThat(event.updatedAt()).isEqualTo(LATER);
  }

  @Test
  void should_change_venue_when_a_new_venue_is_provided() {
    Event event = publishedEvent();

    event.reschedule(NEW_START, NEW_END, " Arena Lima ", clockAt(LATER));

    assertThat(event.venue()).isEqualTo("Arena Lima");
  }

  @Test
  void should_register_event_rescheduled_with_the_resulting_venue_when_venue_is_omitted() {
    Event event = publishedEvent();

    event.reschedule(NEW_START, NEW_END, clockAt(LATER));

    assertThat(event.pullDomainEvents())
        .singleElement()
        .isInstanceOfSatisfying(
            EventRescheduled.class,
            rescheduled -> {
              assertThat(rescheduled.eventId()).isEqualTo(event.id());
              assertThat(rescheduled.startsAt()).isEqualTo(NEW_START);
              assertThat(rescheduled.endsAt()).isEqualTo(NEW_END);
              assertThat(rescheduled.venue()).isEqualTo("Estadio Nacional");
              assertThat(rescheduled.occurredAt()).isEqualTo(LATER);
            });
  }

  @Test
  void should_register_event_rescheduled_with_new_venue_when_venue_changes() {
    Event event = publishedEvent();

    event.reschedule(NEW_START, NEW_END, "Arena Lima", CLOCK);

    assertThat(event.pullDomainEvents())
        .singleElement()
        .isInstanceOfSatisfying(
            EventRescheduled.class, e -> assertThat(e.venue()).isEqualTo("Arena Lima"));
  }

  @Test
  void should_allow_moving_the_event_earlier_when_new_start_is_still_in_the_future() {
    Event event = publishedEvent();
    Instant earlier = NOW.plus(Duration.ofDays(1));

    event.reschedule(earlier, earlier.plus(Duration.ofHours(2)), CLOCK);

    assertThat(event.startsAt()).isEqualTo(earlier);
  }

  @Test
  void should_reject_reschedule_when_event_is_draft() {
    Event event = draftEventWithZone();

    assertThatThrownBy(() -> event.reschedule(NEW_START, NEW_END, CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.DRAFT));
  }

  @Test
  void should_reject_reschedule_when_event_is_cancelled() {
    Event event = cancelledEvent();

    assertThatThrownBy(() -> event.reschedule(NEW_START, NEW_END, CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.CANCELLED));
  }

  @Test
  void should_reject_reschedule_when_event_has_already_started() {
    Event event = publishedEvent();
    Instant afterStart = STARTS_AT.plusSeconds(1);

    assertThatThrownBy(() -> event.reschedule(NEW_START, NEW_END, clockAt(afterStart)))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.EVENT_ALREADY_STARTED));
    assertThat(event.startsAt()).isEqualTo(STARTS_AT);
    assertThat(event.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_reject_reschedule_when_clock_is_exactly_at_the_start_time() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.reschedule(NEW_START, NEW_END, clockAt(STARTS_AT)))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.EVENT_ALREADY_STARTED));
  }

  @Test
  void should_reject_reschedule_when_new_start_is_not_in_the_future() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.reschedule(NOW, NOW.plusSeconds(3600), CLOCK))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.STARTS_AT_NOT_IN_FUTURE));
    assertThat(event.startsAt()).isEqualTo(STARTS_AT);
  }

  @Test
  void should_accept_reschedule_when_new_start_is_one_nanosecond_in_the_future() {
    Event event = publishedEvent();

    event.reschedule(NOW.plusNanos(1), NOW.plusSeconds(3600), CLOCK);

    assertThat(event.startsAt()).isEqualTo(NOW.plusNanos(1));
  }

  @Test
  void should_reject_reschedule_when_ends_at_is_not_after_starts_at() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.reschedule(NEW_START, NEW_START, CLOCK))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("endsAt"));
    assertThat(event.endsAt()).isEqualTo(ENDS_AT);
  }

  @Test
  void should_reject_reschedule_when_new_venue_is_too_short() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.reschedule(NEW_START, NEW_END, "x", CLOCK))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("venue"));
    assertThat(event.venue()).isEqualTo("Estadio Nacional");
  }

  @Test
  void should_reject_reschedule_when_dates_are_missing() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.reschedule(null, NEW_END, CLOCK))
        .isInstanceOf(InvalidValueException.class);
    assertThatThrownBy(() -> event.reschedule(NEW_START, null, CLOCK))
        .isInstanceOf(InvalidValueException.class);
  }
}
