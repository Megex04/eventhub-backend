package com.eventhub.booking.domain;

import static com.eventhub.booking.domain.BookingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.Booking.CaptureOutcome;
import com.eventhub.booking.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BookingPaymentFlowTest {

  private final MutableClock clock = new MutableClock(NOW);

  private Booking payingBooking() {
    Booking booking = pendingBooking();
    booking.beginPayment(PAYMENT, TOKEN, clock);
    booking.pullDomainEvents();
    return booking;
  }

  private Payment paymentOf(Booking booking) {
    return booking.payment().orElseThrow();
  }

  @Test
  void should_start_a_payment_for_exactly_the_total_of_the_booking() {
    Booking booking = pendingBooking();

    Payment payment = booking.beginPayment(PAYMENT, TOKEN, clock);

    assertThat(payment.id()).isEqualTo(PAYMENT);
    assertThat(payment.amount()).isEqualTo(booking.total());
    assertThat(payment.amount().currency()).isEqualTo("PEN");
    assertThat(booking.payment()).contains(payment);
    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void should_refuse_a_second_attempt_while_one_is_in_progress() {
    Booking booking = payingBooking();

    assertThatThrownBy(() -> booking.beginPayment(NEXT_PAYMENT, TOKEN, clock))
        .isInstanceOfSatisfying(
            PaymentInProgressException.class, e -> assertThat(e.paymentId()).isEqualTo(PAYMENT));
    assertThat(paymentOf(booking).id()).isEqualTo(PAYMENT);
  }

  @Test
  void should_allow_a_new_attempt_with_a_new_payment_id_after_a_decline() {
    Booking booking = payingBooking();
    booking.declinePayment();

    Payment retry = booking.beginPayment(NEXT_PAYMENT, TOKEN, clock);

    assertThat(retry.id()).isEqualTo(NEXT_PAYMENT);
    assertThat(retry.status()).isEqualTo(PaymentStatus.INITIATED);
    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void should_keep_the_booking_pending_when_the_payment_is_declined() {
    Booking booking = payingBooking();

    booking.declinePayment();

    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
    assertThat(paymentOf(booking).status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(booking.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_confirm_and_record_the_event_with_the_payment_id_when_the_charge_is_captured() {
    Booking booking = payingBooking();
    clock.advance(Duration.ofMinutes(1));

    CaptureOutcome outcome = booking.capturePayment(clock);

    assertThat(outcome).isEqualTo(CaptureOutcome.CONFIRMED);
    assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.confirmedAt()).contains(NOW.plus(Duration.ofMinutes(1)));
    assertThat(paymentOf(booking).status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(booking.pullDomainEvents())
        .containsExactly(
            new BookingConfirmed(
                BOOKING, USER, EVENT, PAYMENT, total(), NOW.plus(Duration.ofMinutes(1))));
  }

  @Test
  void should_demand_a_refund_when_the_booking_expired_while_the_charge_was_running() {
    Booking booking = payingBooking();
    clock.set(EXPIRES_AT);
    booking.expire(clock);
    booking.pullDomainEvents();

    CaptureOutcome outcome = booking.capturePayment(clock);

    assertThat(outcome).isEqualTo(CaptureOutcome.REFUND_REQUIRED);
    assertThat(booking.status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(paymentOf(booking).status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(paymentOf(booking).refundReason())
        .contains(RefundReason.BOOKING_EXPIRED_DURING_PAYMENT);
  }

  @Test
  void should_demand_a_refund_for_a_booking_cancelled_by_the_user_while_the_charge_ran() {
    Booking booking = payingBooking();
    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, clock);

    assertThat(booking.capturePayment(clock)).isEqualTo(CaptureOutcome.REFUND_REQUIRED);
    assertThat(paymentOf(booking).refundReason()).contains(RefundReason.BOOKING_CANCELLED);
  }

  @Test
  void should_demand_a_refund_for_a_booking_cancelled_with_its_event_while_the_charge_ran() {
    Booking booking = payingBooking();
    booking.cancel(CancelReason.EVENT_CANCELLED, EVENT_STARTS, clock);

    assertThat(booking.capturePayment(clock)).isEqualTo(CaptureOutcome.REFUND_REQUIRED);
    assertThat(paymentOf(booking).refundReason()).contains(RefundReason.EVENT_CANCELLED);
  }

  @Test
  void should_complete_the_refund_and_record_the_refunded_event() {
    Booking booking = payingBooking();
    clock.set(EXPIRES_AT);
    booking.expire(clock);
    booking.capturePayment(clock);
    booking.pullDomainEvents();
    clock.advance(Duration.ofSeconds(5));

    booking.completeRefund(clock);

    assertThat(paymentOf(booking).status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(booking.pullDomainEvents())
        .containsExactly(
            new BookingPaymentRefunded(
                BOOKING,
                USER,
                EVENT,
                PAYMENT,
                total(),
                RefundReason.BOOKING_EXPIRED_DURING_PAYMENT,
                EXPIRES_AT.plusSeconds(5)));
  }

  @Test
  void should_refuse_to_complete_a_refund_that_was_not_requested() {
    Booking booking = payingBooking();

    assertThatThrownBy(() -> booking.completeRefund(clock))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_refuse_to_capture_or_decline_without_a_payment() {
    Booking booking = pendingBooking();

    assertThatThrownBy(() -> booking.capturePayment(clock))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(booking::declinePayment).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> booking.completeRefund(clock))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_refuse_to_capture_the_same_payment_twice() {
    Booking booking = payingBooking();
    booking.capturePayment(clock);

    assertThatThrownBy(() -> booking.capturePayment(clock))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_refuse_to_decline_a_payment_that_was_already_captured() {
    Booking booking = payingBooking();
    booking.capturePayment(clock);

    assertThatThrownBy(booking::declinePayment).isInstanceOf(InvalidStateException.class);
  }
}
