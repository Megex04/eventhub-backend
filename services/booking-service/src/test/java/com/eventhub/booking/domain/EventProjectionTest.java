package com.eventhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.EventProjection.ApplyResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventProjectionTest {

  private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
  private static final UUID ZONE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final Instant T0 = Instant.parse("2026-12-01T10:00:00Z");
  private static final Instant STARTS = Instant.parse("2027-01-10T20:00:00Z");
  private static final Instant ENDS = STARTS.plus(Duration.ofHours(3));
  private static final ProjectedZone VIP =
      new ProjectedZone(ZONE, "VIP", Money.of("150.00", "PEN"), 20);

  private static EventProjection published(Instant occurredAt) {
    return EventProjection.published(EVENT, STARTS, ENDS, List.of(VIP), occurredAt);
  }

  private static Clock at(Instant now) {
    return Clock.fixed(now, ZoneOffset.UTC);
  }

  @Test
  void should_start_open_with_the_published_data() {
    EventProjection projection = published(T0);

    assertThat(projection.status()).isEqualTo(EventSaleStatus.OPEN);
    assertThat(projection.startsAt()).isEqualTo(STARTS);
    assertThat(projection.lastOccurredAt()).isEqualTo(T0);
    assertThat(projection.zone(ZONE)).contains(VIP);
    assertThat(projection.zone(UUID.randomUUID())).isEmpty();
  }

  @Test
  void should_reject_a_projection_without_zones() {
    assertThatThrownBy(() -> EventProjection.published(EVENT, STARTS, ENDS, List.of(), T0))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "zones");
  }

  @Test
  void should_reject_an_end_that_is_not_after_the_start() {
    assertThatThrownBy(() -> EventProjection.published(EVENT, STARTS, STARTS, List.of(VIP), T0))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "endsAt");
  }

  @Test
  void should_be_on_sale_only_when_open_and_not_started() {
    EventProjection projection = published(T0);

    assertThat(projection.isOnSale(at(STARTS.minusSeconds(1)))).isTrue();
    assertThat(projection.isOnSale(at(STARTS))).isFalse();
    assertThat(projection.isOnSale(at(STARTS.plusSeconds(1)))).isFalse();
  }

  @Test
  void should_not_be_on_sale_when_cancelled() {
    EventProjection projection = published(T0);
    projection.applyCancelled(T0.plusSeconds(1));

    assertThat(projection.isOnSale(at(T0.plusSeconds(2)))).isFalse();
  }

  @Test
  void should_apply_a_reschedule_that_is_newer() {
    EventProjection projection = published(T0);
    Instant newStart = STARTS.plus(Duration.ofDays(7));

    ApplyResult result =
        projection.applyRescheduled(
            newStart, newStart.plus(Duration.ofHours(3)), T0.plusSeconds(5));

    assertThat(result).isEqualTo(ApplyResult.APPLIED);
    assertThat(projection.startsAt()).isEqualTo(newStart);
    assertThat(projection.lastOccurredAt()).isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void should_ignore_an_old_reschedule_that_arrives_late() {
    EventProjection projection = published(T0);
    Instant latest = STARTS.plus(Duration.ofDays(14));
    projection.applyRescheduled(latest, latest.plus(Duration.ofHours(3)), T0.plusSeconds(10));

    Instant stale = STARTS.plus(Duration.ofDays(7));
    ApplyResult result =
        projection.applyRescheduled(stale, stale.plus(Duration.ofHours(3)), T0.plusSeconds(5));

    assertThat(result).isEqualTo(ApplyResult.IGNORED_STALE);
    assertThat(projection.startsAt()).isEqualTo(latest);
    assertThat(projection.lastOccurredAt()).isEqualTo(T0.plusSeconds(10));
  }

  @Test
  void should_apply_a_message_with_the_same_timestamp_without_changing_the_outcome() {
    EventProjection projection = published(T0);
    Instant newStart = STARTS.plus(Duration.ofDays(7));
    Instant newEnd = newStart.plus(Duration.ofHours(3));
    projection.applyRescheduled(newStart, newEnd, T0.plusSeconds(5));

    ApplyResult again = projection.applyRescheduled(newStart, newEnd, T0.plusSeconds(5));

    assertThat(again).isEqualTo(ApplyResult.APPLIED);
    assertThat(projection.startsAt()).isEqualTo(newStart);
    assertThat(projection.status()).isEqualTo(EventSaleStatus.OPEN);
  }

  @Test
  void should_accept_a_replayed_publication_with_the_same_timestamp() {
    EventProjection projection = published(T0);

    ApplyResult replay = projection.applyPublished(STARTS, ENDS, List.of(VIP), T0);

    assertThat(replay).isEqualTo(ApplyResult.APPLIED);
    assertThat(projection.status()).isEqualTo(EventSaleStatus.OPEN);
    assertThat(projection.zone(ZONE)).contains(VIP);
  }

  @Test
  void should_cancel_when_the_cancellation_is_newer() {
    EventProjection projection = published(T0);

    ApplyResult result = projection.applyCancelled(T0.plusSeconds(60));

    assertThat(result).isEqualTo(ApplyResult.APPLIED);
    assertThat(projection.status()).isEqualTo(EventSaleStatus.CANCELLED);
  }

  @Test
  void should_cancel_when_the_cancellation_has_the_same_timestamp_as_the_publication() {
    EventProjection projection = published(T0);

    assertThat(projection.applyCancelled(T0)).isEqualTo(ApplyResult.APPLIED);
    assertThat(projection.status()).isEqualTo(EventSaleStatus.CANCELLED);
  }

  @Test
  void should_never_reopen_a_cancelled_event_when_a_replayed_publication_arrives() {
    EventProjection projection = published(T0);
    projection.applyCancelled(T0.plusSeconds(60));

    ApplyResult replay = projection.applyPublished(STARTS, ENDS, List.of(VIP), T0.plusSeconds(120));

    assertThat(replay).isEqualTo(ApplyResult.IGNORED_CANCELLED);
    assertThat(projection.status()).isEqualTo(EventSaleStatus.CANCELLED);
  }

  @Test
  void should_ignore_a_publication_older_than_the_cancellation() {
    EventProjection projection = published(T0);
    projection.applyCancelled(T0.plusSeconds(60));

    assertThat(projection.applyPublished(STARTS, ENDS, List.of(VIP), T0.plusSeconds(30)))
        .isEqualTo(ApplyResult.IGNORED_CANCELLED);
  }

  @Test
  void should_ignore_a_reschedule_after_the_cancellation_even_when_it_is_newer() {
    EventProjection projection = published(T0);
    projection.applyCancelled(T0.plusSeconds(60));
    Instant newStart = STARTS.plus(Duration.ofDays(7));

    ApplyResult result =
        projection.applyRescheduled(
            newStart, newStart.plus(Duration.ofHours(3)), T0.plusSeconds(90));

    assertThat(result).isEqualTo(ApplyResult.IGNORED_CANCELLED);
    assertThat(projection.startsAt()).isEqualTo(STARTS);
  }

  @Test
  void should_report_a_repeated_cancellation_as_already_terminal() {
    EventProjection projection = published(T0);
    projection.applyCancelled(T0.plusSeconds(60));

    assertThat(projection.applyCancelled(T0.plusSeconds(60)))
        .isEqualTo(ApplyResult.IGNORED_CANCELLED);
    assertThat(projection.lastOccurredAt()).isEqualTo(T0.plusSeconds(60));
  }

  @Test
  void should_ignore_a_cancellation_older_than_the_last_applied_message() {
    EventProjection projection = published(T0);
    Instant newStart = STARTS.plus(Duration.ofDays(7));
    projection.applyRescheduled(newStart, newStart.plus(Duration.ofHours(3)), T0.plusSeconds(60));

    assertThat(projection.applyCancelled(T0.plusSeconds(30))).isEqualTo(ApplyResult.IGNORED_STALE);
    assertThat(projection.status()).isEqualTo(EventSaleStatus.OPEN);
  }

  @Test
  void should_not_record_the_timestamp_when_a_message_is_ignored() {
    EventProjection projection = published(T0);

    projection.applyCancelled(T0.minusSeconds(1));

    assertThat(projection.lastOccurredAt()).isEqualTo(T0);
  }

  @Test
  void should_rebuild_the_exact_state_when_restored() {
    EventProjection restored =
        EventProjection.restore(
            EVENT, EventSaleStatus.CANCELLED, STARTS, ENDS, List.of(VIP), T0.plusSeconds(9));

    assertThat(restored.status()).isEqualTo(EventSaleStatus.CANCELLED);
    assertThat(restored.lastOccurredAt()).isEqualTo(T0.plusSeconds(9));
    assertThat(restored.zones()).containsExactly(VIP);
  }

  @Test
  void should_reject_a_zone_with_a_capacity_below_one() {
    assertThatThrownBy(() -> new ProjectedZone(ZONE, "VIP", Money.of("1.00", "PEN"), 0))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "capacity");
  }
}
