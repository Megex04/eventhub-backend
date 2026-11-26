package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DelegatingBookingRepository;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingCancelled;
import com.eventhub.booking.domain.BookingPaymentRefunded;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.EventSaleStatus;
import com.eventhub.booking.domain.PaymentStatus;
import com.eventhub.booking.domain.RefundReason;
import com.eventhub.booking.domain.RequestedItem;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventCancelledCascadeTest {

  private static final EventCancelledMessage CANCEL =
      new EventCancelledMessage(uuid(7000), EVENT, NOW.plusSeconds(30));

  private final AppEnv env = new AppEnv().withOpenEvent();

  private ApplyEventCancelledService cascade(BookingRepository bookings, int batchSize) {
    return new ApplyEventCancelledService(
        env.tx,
        env.projections,
        bookings,
        env.inventory,
        env.processed,
        env.publisher,
        env.clock,
        batchSize);
  }

  private void book(long id, int quantity, boolean paid) {
    Booking booking = newBooking(uuid(id), USER, env.clock, new RequestedItem(VIP_ZONE, quantity));
    booking.pullDomainEvents();
    env.bookings.saveNew(booking, new IdempotencyRecord(USER, uuid(900 + id), "fp" + id));
    env.inventory.reserve(EVENT, booking.requestedItems());
    if (paid) {
      new PayBookingService(env.tx, env.bookings, env.publisher, env.ids, env.gateway, env.clock)
          .pay(new PayBookingCommand(USER, uuid(id), "tok_approved"));
    }
  }

  @Test
  void
      should_cancel_pending_and_confirmed_bookings_with_the_event_reason_and_release_their_tickets() {
    book(1, 2, false);
    book(2, 3, true);

    cascade(env.bookings, 10).apply(CANCEL);

    for (long id : new long[] {1, 2}) {
      Booking booking = env.booking(uuid(id));
      assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
      assertThat(booking.cancelReason()).contains(CancelReason.EVENT_CANCELLED);
    }
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingCancelled.class))
        .hasSize(2)
        .allSatisfy(event -> assertThat(event.reason()).isEqualTo(CancelReason.EVENT_CANCELLED));
    assertThat(env.projections.findById(EVENT).orElseThrow().status())
        .isEqualTo(EventSaleStatus.CANCELLED);
  }

  @Test
  void should_leave_the_refund_to_the_reconciler_and_not_call_the_gateway_in_the_cascade() {
    book(1, 2, true);
    int refundCallsBefore = env.gateway.refundCalls();

    cascade(env.bookings, 10).apply(CANCEL);

    assertThat(env.gateway.refundCalls()).isEqualTo(refundCallsBefore);
    var payment = env.booking(uuid(1)).payment().orElseThrow();
    assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(payment.refundReason()).contains(RefundReason.EVENT_CANCELLED);
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class)).isEmpty();
    assertThat(env.gateway.holds(payment.id())).isTrue();
  }

  @Test
  void should_cancel_every_booking_when_there_are_more_than_one_batch() {
    for (long id = 1; id <= 5; id++) {
      book(id, 1, id % 2 == 0);
    }

    cascade(env.bookings, 2).apply(CANCEL);

    for (long id = 1; id <= 5; id++) {
      assertThat(env.booking(uuid(id)).status()).isEqualTo(BookingStatus.CANCELLED);
    }
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingCancelled.class)).hasSize(5);
  }

  @Test
  void should_not_touch_the_bookings_that_were_already_finished() {
    book(1, 2, false);
    Booking expired = env.booking(uuid(1));
    env.clock.set(EXPIRES_AT);
    expired.expire(env.clock);
    env.bookings.save(expired);
    env.inventory.release(EVENT, expired.requestedItems());
    book(2, 1, false);

    cascade(env.bookings, 10).apply(CANCEL);

    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(env.booking(uuid(2)).status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(env.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_mark_the_message_processed_only_after_the_whole_cascade() {
    book(1, 1, false);
    book(2, 1, false);

    cascade(env.bookings, 10).apply(CANCEL);

    assertThat(env.processed.isProcessed(CANCEL.messageId())).isTrue();
  }

  @Test
  void should_resume_where_it_stopped_when_the_message_is_redelivered_after_a_failure() {
    for (long id = 1; id <= 4; id++) {
      book(id, 1, false);
    }
    FailingNthSave failing = new FailingNthSave(env.bookings, 3);

    assertThatThrownBy(() -> cascade(failing, 10).apply(CANCEL))
        .isInstanceOf(IllegalStateException.class);

    long cancelledSoFar =
        java.util.stream.LongStream.rangeClosed(1, 4)
            .filter(id -> env.booking(uuid(id)).status() == BookingStatus.CANCELLED)
            .count();
    assertThat(cancelledSoFar).isEqualTo(2);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
    assertThat(env.processed.isProcessed(CANCEL.messageId())).isFalse();

    cascade(env.bookings, 10).apply(CANCEL);

    for (long id = 1; id <= 4; id++) {
      assertThat(env.booking(uuid(id)).status()).isEqualTo(BookingStatus.CANCELLED);
    }
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingCancelled.class)).hasSize(4);
    assertThat(env.processed.isProcessed(CANCEL.messageId())).isTrue();
  }

  @Test
  void should_do_nothing_when_the_same_message_is_delivered_again_after_it_finished() {
    book(1, 1, false);
    cascade(env.bookings, 10).apply(CANCEL);
    book(2, 1, false);

    cascade(env.bookings, 10).apply(CANCEL);

    assertThat(env.booking(uuid(2)).status()).isEqualTo(BookingStatus.PENDING);
    assertThat(env.publisher.eventsOfType(BookingCancelled.class)).hasSize(1);
  }

  @Test
  void
      should_not_cancel_any_booking_when_the_cancellation_is_older_than_the_last_applied_message() {
    book(1, 1, false);
    EventProjection projection = env.projections.findById(EVENT).orElseThrow();
    projection.applyRescheduled(EVENT_STARTS, EVENT_STARTS.plusSeconds(3600), NOW.plusSeconds(100));
    env.projections.save(projection);

    cascade(env.bookings, 10).apply(CANCEL);

    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.PENDING);
    assertThat(env.projections.findById(EVENT).orElseThrow().status())
        .isEqualTo(EventSaleStatus.OPEN);
  }

  @Test
  void should_cancel_the_bookings_of_that_event_only() {
    book(1, 1, false);
    UUID other = uuid(777);
    Booking foreign =
        com.eventhub.booking.domain.Booking.restore(
            uuid(50),
            USER,
            other,
            BookingStatus.PENDING,
            env.booking(uuid(1)).items(),
            env.booking(uuid(1)).total(),
            NOW,
            EXPIRES_AT,
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            0);
    env.bookings.saveNew(foreign, new IdempotencyRecord(USER, uuid(951), "fp-other"));

    cascade(env.bookings, 10).apply(CANCEL);

    assertThat(env.booking(uuid(50)).status()).isEqualTo(BookingStatus.PENDING);
  }

  /** Lets the first saves through and then breaks, as a crash in the middle of the cascade. */
  private static final class FailingNthSave extends DelegatingBookingRepository {
    private final int failOn;
    private int saves;

    FailingNthSave(BookingRepository delegate, int failOn) {
      super(delegate);
      this.failOn = failOn;
    }

    @Override
    public Booking save(Booking booking) {
      saves++;
      if (saves == failOn) {
        throw new IllegalStateException("simulated crash");
      }
      return delegate.save(booking);
    }
  }
}
