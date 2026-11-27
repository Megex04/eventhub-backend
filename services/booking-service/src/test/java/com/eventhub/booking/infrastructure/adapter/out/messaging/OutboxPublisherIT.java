package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingExpired;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.MutableClock;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.IllegalTransactionStateException;

/** The write side of the outbox, on its own: what it stamps and what it refuses. */
@PersistenceIT
class OutboxPublisherIT {

  @Autowired private ApplicationContext context;
  @Autowired private DomainEventPublisher publisher;
  @Autowired private MutableClock clock;
  private RealApp app;

  @BeforeEach
  void wire() {
    app = new RealApp(context);
  }

  private static List<DomainEvent> expiredEventOf(UUID bookingId) {
    Booking booking = newBooking(bookingId, USER, at(NOW), new RequestedItem(VIP_ZONE, 1));
    booking.pullDomainEvents();
    booking.expire(at(EXPIRES_AT));
    return booking.pullDomainEvents();
  }

  private List<OffsetDateTime> creationInstants(UUID aggregate) {
    return app.jdbc.queryForList(
        "SELECT created_at FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, id",
        OffsetDateTime.class,
        aggregate);
  }

  @Test
  void should_refuse_to_write_an_event_outside_a_transaction() {
    assertThatThrownBy(() -> publisher.publish(expiredEventOf(uuid(1))))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(app.count("SELECT count(*) FROM outbox_event")).isZero();
  }

  @Test
  void should_store_the_row_with_the_event_type_and_the_booking_as_aggregate() {
    List<DomainEvent> events = expiredEventOf(uuid(1));

    app.tx.run(() -> publisher.publish(events));

    assertThat(
            app.jdbc.queryForMap(
                "SELECT aggregate_type, aggregate_id, event_type, event_version, attempts,"
                    + " published_at FROM outbox_event"))
        .containsEntry("aggregate_type", "Booking")
        .containsEntry("aggregate_id", uuid(1))
        .containsEntry("event_type", "BookingExpired")
        .containsEntry("event_version", 1)
        .containsEntry("attempts", 0)
        .containsEntry("published_at", null);
  }

  @Test
  void should_order_the_events_of_one_booking_strictly_even_when_the_clock_does_not_move() {
    UUID booking = uuid(1);
    BookingExpired first = (BookingExpired) expiredEventOf(booking).get(0);

    app.tx.run(() -> publisher.publish(List.of(first)));
    app.tx.run(() -> publisher.publish(List.of(first)));
    app.tx.run(() -> publisher.publish(List.of(first, first)));

    List<OffsetDateTime> instants = creationInstants(booking);
    assertThat(instants).hasSize(4);
    for (int i = 1; i < instants.size(); i++) {
      assertThat(instants.get(i))
          .as("event %d is strictly after event %d", i, i - 1)
          .isAfter(instants.get(i - 1));
    }
  }

  @Test
  void should_keep_the_order_of_one_booking_when_the_clock_goes_back() {
    UUID booking = uuid(1);
    BookingExpired event = (BookingExpired) expiredEventOf(booking).get(0);
    clock.advance(Duration.ofHours(1));
    app.tx.run(() -> publisher.publish(List.of(event)));

    clock.advance(Duration.ofHours(-2));
    app.tx.run(() -> publisher.publish(List.of(event)));

    List<OffsetDateTime> instants = creationInstants(booking);
    assertThat(instants.get(1)).isAfter(instants.get(0));
  }

  @Test
  void should_not_hold_back_the_events_of_another_booking() {
    BookingExpired a = (BookingExpired) expiredEventOf(uuid(1)).get(0);
    BookingExpired b = (BookingExpired) expiredEventOf(uuid(2)).get(0);
    clock.advance(Duration.ofHours(1));
    app.tx.run(() -> publisher.publish(List.of(a)));

    clock.advance(Duration.ofHours(-1));
    app.tx.run(() -> publisher.publish(List.of(b)));

    assertThat(creationInstants(uuid(2)).get(0).toInstant())
        .as("the clock decides, because this booking has no earlier event")
        .isEqualTo(clock.instant());
  }

  @Test
  void should_record_the_correlation_id_of_the_request_when_there_is_one() {
    UUID correlation = UUID.randomUUID();
    MDC.put("correlationId", correlation.toString());
    try {
      app.tx.run(() -> publisher.publish(expiredEventOf(uuid(1))));
    } finally {
      MDC.remove("correlationId");
    }

    assertThat(app.jdbc.queryForObject("SELECT correlation_id FROM outbox_event", UUID.class))
        .isEqualTo(correlation);
  }

  @Test
  void should_generate_a_correlation_id_when_no_request_is_running() {
    app.tx.run(() -> publisher.publish(expiredEventOf(uuid(1))));

    assertThat(app.jdbc.queryForObject("SELECT correlation_id FROM outbox_event", UUID.class))
        .isNotNull();
  }

  @Test
  void should_write_nothing_when_the_transaction_rolls_back() {
    List<DomainEvent> events = expiredEventOf(uuid(1));

    assertThatThrownBy(() ->
            app.tx.run(() -> {
              publisher.publish(events);
              throw new IllegalStateException("fails after the outbox write");
            }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(app.count("SELECT count(*) FROM outbox_event")).isZero();
  }
}
