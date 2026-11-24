package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.cancelledEvent;
import static com.eventhub.catalog.domain.EventFixtures.clockAt;
import static com.eventhub.catalog.domain.EventFixtures.draftEventWithZone;
import static com.eventhub.catalog.domain.EventFixtures.publishedEvent;
import static com.eventhub.catalog.domain.EventFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventCancelTest {

  private static final Instant LATER = NOW.plus(Duration.ofHours(1));

  @Test
  void should_move_to_cancelled_and_store_reason_when_event_is_published() {
    Event event = publishedEvent();

    event.cancel("Artist is sick", clockAt(LATER));

    assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
    assertThat(event.cancelReason()).contains("Artist is sick");
    assertThat(event.updatedAt()).isEqualTo(LATER);
  }

  @Test
  void should_register_event_cancelled_when_event_is_cancelled() {
    Event event = publishedEvent();

    event.cancel("  Artist is sick ", clockAt(LATER));

    assertThat(event.pullDomainEvents())
        .singleElement()
        .isInstanceOfSatisfying(
            EventCancelled.class,
            cancelled -> {
              assertThat(cancelled.eventId()).isEqualTo(event.id());
              assertThat(cancelled.reason()).isEqualTo("Artist is sick");
              assertThat(cancelled.occurredAt()).isEqualTo(LATER);
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 500})
  void should_accept_reason_when_length_is_at_the_bounds(int length) {
    Event event = publishedEvent();

    event.cancel("r".repeat(length), CLOCK);

    assertThat(event.cancelReason())
        .hasValueSatisfying(reason -> assertThat(reason).hasSize(length));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 501})
  void should_reject_reason_when_length_is_out_of_bounds(int length) {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.cancel("r".repeat(length), CLOCK))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    assertThat(event.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_reject_cancel_when_reason_is_null() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.cancel(null, CLOCK)).isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_cancel_when_event_is_draft() {
    Event event = draftEventWithZone();

    assertThatThrownBy(() -> event.cancel("Not needed anymore", CLOCK))
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.DRAFT));
  }

  @Test
  void should_reject_cancel_when_event_is_already_cancelled_because_state_is_terminal() {
    Event event = cancelledEvent();

    assertThatThrownBy(() -> event.cancel("Cancelling again", CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThat(event.cancelReason()).contains("Venue unavailable");
  }

  @Test
  void should_reject_every_change_when_event_is_cancelled() {
    Event event = cancelledEvent();

    assertThatThrownBy(() -> event.update("New title", "", "New venue", STARTS_AT, ENDS_AT, CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> event.addZone(zone("Late"), CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> event.publish(CLOCK)).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> event.reschedule(STARTS_AT, ENDS_AT, CLOCK))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_allow_cancel_when_published_event_has_already_started() {
    Event event = publishedEvent();

    event.cancel("Emergency during the show", clockAt(STARTS_AT.plusSeconds(60)));

    assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
  }

  @Test
  void should_have_no_cancel_reason_when_event_is_not_cancelled() {
    assertThat(publishedEvent().cancelReason()).isEmpty();
  }
}
