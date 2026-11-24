package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.cancelledEvent;
import static com.eventhub.catalog.domain.EventFixtures.clockAt;
import static com.eventhub.catalog.domain.EventFixtures.draftEvent;
import static com.eventhub.catalog.domain.EventFixtures.publishedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventUpdateTest {

  private static final Instant LATER = NOW.plus(Duration.ofHours(1));

  @Test
  void should_replace_data_and_touch_updated_at_when_event_is_draft() {
    Event event = draftEvent();
    Instant newStart = STARTS_AT.plus(Duration.ofDays(1));
    Instant newEnd = ENDS_AT.plus(Duration.ofDays(1));

    event.update("New title", "New description", "New venue", newStart, newEnd, clockAt(LATER));

    assertThat(event.title()).isEqualTo("New title");
    assertThat(event.description()).isEqualTo("New description");
    assertThat(event.venue()).isEqualTo("New venue");
    assertThat(event.startsAt()).isEqualTo(newStart);
    assertThat(event.endsAt()).isEqualTo(newEnd);
    assertThat(event.updatedAt()).isEqualTo(LATER);
    assertThat(event.createdAt()).isEqualTo(NOW);
    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
  }

  @Test
  void should_not_register_domain_events_when_draft_is_updated() {
    Event event = draftEvent();

    event.update("New title", "", "New venue", STARTS_AT, ENDS_AT, CLOCK);

    assertThat(event.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_reject_update_when_event_is_published() {
    Event event = publishedEvent();

    assertThatThrownBy(() -> event.update("New title", "", "New venue", STARTS_AT, ENDS_AT, CLOCK))
        .isInstanceOf(InvalidStateException.class);
    assertThat(event.title()).isEqualTo("Rock Fest");
  }

  @Test
  void should_reject_update_when_event_is_cancelled() {
    Event event = cancelledEvent();

    assertThatThrownBy(() -> event.update("New title", "", "New venue", STARTS_AT, ENDS_AT, CLOCK))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_reject_update_and_keep_data_when_values_are_invalid() {
    Event event = draftEvent();

    assertThatThrownBy(() -> event.update("ab", "", "New venue", STARTS_AT, ENDS_AT, CLOCK))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("title"));
    assertThat(event.title()).isEqualTo("Rock Fest");
    assertThat(event.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void should_reject_update_when_ends_at_is_not_after_starts_at() {
    Event event = draftEvent();

    assertThatThrownBy(
            () -> event.update("New title", "", "New venue", STARTS_AT, STARTS_AT, CLOCK))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("endsAt"));
    assertThat(event.startsAt()).isEqualTo(STARTS_AT);
  }
}
