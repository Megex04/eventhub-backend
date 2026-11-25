package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Replaying describes the event as it is now; it never changes the event. */
class EventReplayTest {

  @Test
  void should_return_one_published_event_with_the_current_data_when_the_event_is_published() {
    Event event = EventFixtures.publishedEvent();

    List<DomainEvent> replayed = event.recordReplay();

    assertThat(replayed).hasSize(1);
    EventPublished published = (EventPublished) replayed.getFirst();
    assertThat(published.eventId()).isEqualTo(event.id());
    assertThat(published.organizerId()).isEqualTo(event.organizerId());
    assertThat(published.title()).isEqualTo(event.title());
    assertThat(published.venue()).isEqualTo(event.venue());
    assertThat(published.startsAt()).isEqualTo(event.startsAt());
    assertThat(published.endsAt()).isEqualTo(event.endsAt());
    assertThat(published.zones())
        .extracting(EventPublished.Zone::zoneId)
        .containsExactlyElementsOf(event.zones().stream().map(TicketZone::id).toList());
    assertThat(published.occurredAt()).isEqualTo(event.updatedAt());
  }

  @Test
  void should_carry_the_new_dates_and_venue_when_the_event_was_moved_after_publishing() {
    Event event = EventFixtures.publishedEvent();
    Instant later = NOW.plus(Duration.ofHours(1));
    Instant newStart = NOW.plus(Duration.ofDays(45));
    event.reschedule(newStart, newStart.plus(Duration.ofHours(3)), "Arena Lima", clockAt(later));

    EventPublished published = (EventPublished) event.recordReplay().getFirst();

    assertThat(published.startsAt()).isEqualTo(newStart);
    assertThat(published.endsAt()).isEqualTo(newStart.plus(Duration.ofHours(3)));
    assertThat(published.venue()).isEqualTo("Arena Lima");
    assertThat(published.occurredAt()).as("last change of the event").isEqualTo(later);
  }

  @Test
  void should_return_published_then_cancelled_when_the_event_is_cancelled() {
    Event event = EventFixtures.cancelledEvent();

    List<DomainEvent> replayed = event.recordReplay();

    assertThat(replayed).hasSize(2);
    assertThat(replayed.get(0)).isInstanceOf(EventPublished.class);
    EventCancelled cancelled = (EventCancelled) replayed.get(1);
    assertThat(cancelled.eventId()).isEqualTo(event.id());
    assertThat(cancelled.reason()).isEqualTo("Venue unavailable");
    assertThat(cancelled.occurredAt())
        .as("the real cancellation time")
        .isEqualTo(event.updatedAt());
    assertThat(((EventPublished) replayed.get(0)).zones()).hasSize(event.zones().size());
  }

  @Test
  void should_reject_the_replay_when_the_event_is_a_draft() {
    Event draft = EventFixtures.draftEventWithZone();

    assertThatThrownBy(draft::recordReplay).isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_leave_the_event_untouched_when_it_is_replayed() {
    Event event = EventFixtures.publishedEvent();
    long version = event.version();
    Instant updatedAt = event.updatedAt();

    event.recordReplay();

    assertThat(event.version()).isEqualTo(version);
    assertThat(event.updatedAt()).isEqualTo(updatedAt);
    assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    assertThat(event.pullDomainEvents()).as("a replay is not a pending domain event").isEmpty();
  }

  @Test
  void should_return_equal_events_when_the_event_is_replayed_twice() {
    Event event = EventFixtures.cancelledEvent();

    assertThat(event.recordReplay()).isEqualTo(event.recordReplay());
  }
}
