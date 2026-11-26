package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.exception.EventProjectionMissingException;
import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.in.EventPublishedMessage;
import com.eventhub.booking.application.port.in.EventRescheduledMessage;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.EventSaleStatus;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.ProjectedZone;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventConsumersTest {

  private static final Instant ENDS = EVENT_STARTS.plus(Duration.ofHours(3));

  private final AppEnv env = new AppEnv();
  private final ApplyEventPublishedService published =
      new ApplyEventPublishedService(env.tx, env.projections, env.inventory, env.processed);
  private final ApplyEventRescheduledService rescheduled =
      new ApplyEventRescheduledService(env.tx, env.projections, env.processed);
  private final ApplyEventCancelledService cancelled =
      new ApplyEventCancelledService(
          env.tx,
          env.projections,
          env.bookings,
          env.inventory,
          env.processed,
          env.publisher,
          env.clock,
          2);

  private static EventPublishedMessage publication(long messageId, Instant occurredAt) {
    return new EventPublishedMessage(
        uuid(messageId), EVENT, EVENT_STARTS, ENDS, List.of(VIP, GENERAL), occurredAt);
  }

  private static EventRescheduledMessage reschedule(
      long messageId, Instant startsAt, Instant occurredAt) {
    return new EventRescheduledMessage(
        uuid(messageId), EVENT, startsAt, startsAt.plus(Duration.ofHours(3)), occurredAt);
  }

  private static EventCancelledMessage cancellation(long messageId, Instant occurredAt) {
    return new EventCancelledMessage(uuid(messageId), EVENT, occurredAt);
  }

  private EventProjection projection() {
    return env.projections.findById(EVENT).orElseThrow();
  }

  // ---- EventPublished ----

  @Test
  void should_project_the_event_and_open_the_inventory_of_every_zone_when_it_is_published() {
    published.apply(publication(1, NOW));

    EventProjection projection = projection();
    assertThat(projection.status()).isEqualTo(EventSaleStatus.OPEN);
    assertThat(projection.zones()).containsExactlyInAnyOrder(VIP, GENERAL);
    assertThat(projection.lastOccurredAt()).isEqualTo(NOW);
    assertThat(env.inventory.findByEvent(EVENT))
        .extracting(zone -> zone.capacity() + "/" + zone.reserved())
        .containsExactlyInAnyOrder("20/0", "100/0");
    assertThat(env.processed.isProcessed(uuid(1))).isTrue();
  }

  @Test
  void should_ignore_a_message_that_was_already_processed() {
    published.apply(publication(1, NOW));
    ProjectedZone bigger = new ProjectedZone(VIP_ZONE, "VIP", Money.of("150.00", "PEN"), 999);

    published.apply(
        new EventPublishedMessage(
            uuid(1), EVENT, EVENT_STARTS, ENDS, List.of(bigger, GENERAL), NOW.plusSeconds(5)));

    assertThat(projection().lastOccurredAt()).isEqualTo(NOW);
    assertThat(env.inventory.findByEvent(EVENT)).extracting(z -> z.capacity()).contains(20);
  }

  @Test
  void should_not_reset_the_reserved_tickets_when_the_publication_is_replayed() {
    published.apply(publication(1, NOW));
    env.inventory.reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 5)));

    published.apply(publication(2, NOW));
    published.apply(publication(3, NOW.plusSeconds(60)));

    assertThat(env.reserved(VIP_ZONE)).isEqualTo(5);
    assertThat(projection().lastOccurredAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void should_not_reopen_a_cancelled_event_when_the_publication_is_replayed() {
    published.apply(publication(1, NOW));
    cancelled.apply(cancellation(2, NOW.plusSeconds(10)));

    published.apply(publication(3, NOW.plusSeconds(60)));

    assertThat(projection().status()).isEqualTo(EventSaleStatus.CANCELLED);
    assertThat(env.processed.isProcessed(uuid(3))).isTrue();
  }

  @Test
  void should_ignore_a_publication_older_than_what_was_applied() {
    published.apply(publication(1, NOW.plusSeconds(60)));
    Instant later = EVENT_STARTS.plus(Duration.ofDays(7));
    rescheduled.apply(reschedule(2, later, NOW.plusSeconds(90)));

    published.apply(publication(3, NOW));

    assertThat(projection().startsAt()).contains(later);
  }

  @Test
  void should_ignore_a_publication_that_comes_after_a_cancellation_that_arrived_first() {
    cancelled.apply(cancellation(1, NOW));

    published.apply(publication(2, NOW.minusSeconds(60)));
    published.apply(publication(3, NOW.plusSeconds(60)));

    assertThat(projection().isTombstone()).isTrue();
    assertThat(env.inventory.findByEvent(EVENT)).isEmpty();
  }

  // ---- EventRescheduled ----

  @Test
  void should_move_the_event_when_it_is_rescheduled() {
    published.apply(publication(1, NOW));
    Instant newStart = EVENT_STARTS.plus(Duration.ofDays(7));

    rescheduled.apply(reschedule(2, newStart, NOW.plusSeconds(10)));

    assertThat(projection().startsAt()).contains(newStart);
    assertThat(projection().lastOccurredAt()).isEqualTo(NOW.plusSeconds(10));
  }

  @Test
  void should_fail_and_not_mark_the_message_when_the_event_was_never_published_here() {
    assertThatThrownBy(() -> rescheduled.apply(reschedule(1, EVENT_STARTS, NOW)))
        .isInstanceOf(EventProjectionMissingException.class);

    assertThat(env.processed.isProcessed(uuid(1))).isFalse();
  }

  @Test
  void should_apply_the_redelivered_reschedule_once_the_publication_has_arrived() {
    EventRescheduledMessage message =
        reschedule(1, EVENT_STARTS.plus(Duration.ofDays(7)), NOW.plusSeconds(10));
    assertThatThrownBy(() -> rescheduled.apply(message))
        .isInstanceOf(EventProjectionMissingException.class);
    published.apply(publication(2, NOW));

    rescheduled.apply(message);

    assertThat(projection().startsAt()).contains(EVENT_STARTS.plus(Duration.ofDays(7)));
    assertThat(env.processed.isProcessed(uuid(1))).isTrue();
  }

  @Test
  void should_ignore_an_old_reschedule_that_arrives_late() {
    published.apply(publication(1, NOW));
    Instant latest = EVENT_STARTS.plus(Duration.ofDays(14));
    rescheduled.apply(reschedule(2, latest, NOW.plusSeconds(20)));

    rescheduled.apply(reschedule(3, EVENT_STARTS.plus(Duration.ofDays(7)), NOW.plusSeconds(10)));

    assertThat(projection().startsAt()).contains(latest);
  }

  @Test
  void should_ignore_a_reschedule_of_a_cancelled_event_without_failing() {
    published.apply(publication(1, NOW));
    cancelled.apply(cancellation(2, NOW.plusSeconds(10)));

    rescheduled.apply(reschedule(3, EVENT_STARTS.plus(Duration.ofDays(7)), NOW.plusSeconds(20)));

    assertThat(projection().startsAt()).contains(EVENT_STARTS);
    assertThat(env.processed.isProcessed(uuid(3))).isTrue();
  }

  @Test
  void should_ignore_a_reschedule_that_was_already_processed() {
    published.apply(publication(1, NOW));
    Instant newStart = EVENT_STARTS.plus(Duration.ofDays(7));
    rescheduled.apply(reschedule(2, newStart, NOW.plusSeconds(10)));

    rescheduled.apply(reschedule(2, EVENT_STARTS.plus(Duration.ofDays(30)), NOW.plusSeconds(99)));

    assertThat(projection().startsAt()).contains(newStart);
  }

  // ---- a cancellation that arrives first ----

  @Test
  void should_keep_a_tombstone_that_makes_the_availability_unknown_when_the_cancel_comes_first() {
    cancelled.apply(cancellation(1, NOW));

    assertThat(projection().isTombstone()).isTrue();
    assertThat(projection().lastOccurredAt()).isEqualTo(NOW);
    assertThatThrownBy(
            () -> new GetAvailabilityService(env.projections, env.inventory, env.clock).get(EVENT))
        .isInstanceOf(NotFoundException.class);
    assertThat(env.processed.isProcessed(uuid(1))).isTrue();
  }
}
