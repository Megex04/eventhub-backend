package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingCancelled;
import com.eventhub.booking.domain.BookingPaymentRefunded;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.BusinessRuleViolationException;
import com.eventhub.booking.domain.BusinessRuleViolationException.Rule;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.InvalidStateException;
import com.eventhub.booking.domain.PaymentStatus;
import com.eventhub.booking.domain.RefundReason;
import com.eventhub.booking.domain.RequestedItem;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancelBookingServiceTest {

  private static final UUID BOOKING = uuid(1);

  private final AppEnv env = new AppEnv().withOpenEvent();
  private final CancelBookingService service =
      new CancelBookingService(
          env.tx,
          env.bookings,
          env.inventory,
          env.projections,
          env.publisher,
          env.gateway,
          env.clock,
          env.metrics);

  private void pendingBooking() {
    Booking booking = newBooking(BOOKING, USER, env.clock, new RequestedItem(VIP_ZONE, 2));
    booking.pullDomainEvents();
    env.bookings.saveNew(booking, new IdempotencyRecord(USER, uuid(900), "fp"));
    env.inventory.reserve(EVENT, booking.requestedItems());
  }

  private void confirmedBooking() {
    pendingBooking();
    new PayBookingService(
            env.tx, env.bookings, env.publisher, env.ids, env.gateway, env.clock, env.metrics)
        .pay(new PayBookingCommand(USER, BOOKING, "tok_approved"));
    env.publisher.events(); // the payment events are not what these tests look at
  }

  @Test
  void should_cancel_a_pending_booking_and_release_its_tickets() {
    pendingBooking();

    Booking result = service.cancel(USER, BOOKING);

    assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(result.cancelReason()).contains(CancelReason.USER_REQUEST);
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingCancelled.class)).hasSize(1);
    assertThat(env.gateway.chargeCalls()).isZero();
    assertThat(env.gateway.refundCalls()).isZero();
  }

  @Test
  void should_refund_at_once_when_a_confirmed_booking_is_cancelled_by_the_user() {
    confirmedBooking();

    Booking result = service.cancel(USER, BOOKING);

    assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(result.payment().orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(env.gateway.distinctRefunds()).isEqualTo(1);
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingCancelled.class)).hasSize(1);
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class))
        .singleElement()
        .satisfies(event -> assertThat(event.reason()).isEqualTo(RefundReason.BOOKING_CANCELLED));
  }

  @Test
  void should_cancel_and_leave_the_refund_pending_when_the_gateway_cannot_refund_now() {
    confirmedBooking();
    env.gateway.failAllRefunds(true);

    Booking result = service.cancel(USER, BOOKING);

    assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(result.payment().orElseThrow().status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class)).isEmpty();
    assertThat(env.gateway.holds(result.payment().orElseThrow().id())).isTrue();
    assertThat(env.metrics.immediateRefundFailures()).containsExactly(BOOKING);
  }

  @Test
  void should_answer_with_the_cancelled_booking_when_the_refund_cannot_be_recorded() {
    confirmedBooking();
    CancelBookingService recordingFails =
        new CancelBookingService(
            env.tx,
            env.bookings,
            env.inventory,
            env.projections,
            new FailingRefundEventsPublisher(env.publisher),
            env.gateway,
            env.clock,
            env.metrics);

    Booking result = recordingFails.cancel(USER, BOOKING);

    assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(result.payment().orElseThrow().status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(env.reserved(VIP_ZONE)).as("released with the cancellation").isZero();
    assertThat(env.gateway.wasRefunded(result.payment().orElseThrow().id())).isTrue();
    assertThat(env.metrics.immediateRefundFailures()).containsExactly(BOOKING);
  }

  @Test
  void should_not_count_a_failed_immediate_refund_when_the_refund_completes() {
    confirmedBooking();

    service.cancel(USER, BOOKING);

    assertThat(env.metrics.immediateRefundFailures()).isEmpty();
  }

  @Test
  void should_refuse_a_confirmed_booking_once_the_event_started_and_change_nothing() {
    confirmedBooking();
    env.clock.set(EVENT_STARTS);

    assertThatThrownBy(() -> service.cancel(USER, BOOKING))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.CANCEL_AFTER_START));

    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
    assertThat(env.gateway.refundCalls()).isZero();
  }

  @Test
  void should_not_release_twice_when_the_booking_is_cancelled_again() {
    pendingBooking();
    service.cancel(USER, BOOKING);

    assertThatThrownBy(() -> service.cancel(USER, BOOKING))
        .isInstanceOf(InvalidStateException.class);

    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingCancelled.class)).hasSize(1);
  }

  @Test
  void should_refuse_to_cancel_an_expired_booking_and_keep_the_inventory_as_it_is() {
    pendingBooking();
    env.clock.set(EXPIRES_AT);
    new ExpireBookingsService(env.tx, env.bookings, env.inventory, env.publisher, env.clock)
        .expireDue(10);

    assertThatThrownBy(() -> service.cancel(USER, BOOKING))
        .isInstanceOf(InvalidStateException.class);
    assertThat(env.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_answer_not_found_when_the_booking_is_someone_elses() {
    confirmedBooking();

    assertThatThrownBy(() -> service.cancel(OTHER_USER, BOOKING))
        .isInstanceOf(NotFoundException.class);

    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.gateway.refundCalls()).isZero();
  }

  @Test
  void should_answer_not_found_when_the_booking_does_not_exist() {
    assertThatThrownBy(() -> service.cancel(USER, uuid(99))).isInstanceOf(NotFoundException.class);
  }
}
