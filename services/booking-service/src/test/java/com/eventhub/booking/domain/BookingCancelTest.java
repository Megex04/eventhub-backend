package com.eventhub.booking.domain;

import static com.eventhub.booking.domain.BookingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.BusinessRuleViolationException.Rule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BookingCancelTest {

  private static final Instant LATER = NOW.plusSeconds(30);

  @Test
  void should_cancel_a_pending_booking_without_touching_any_payment() {
    Booking booking = pendingBooking();
    booking.pullDomainEvents();

    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(LATER));

    assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.cancelledAt()).contains(LATER);
    assertThat(booking.cancelReason()).contains(CancelReason.USER_REQUEST);
    assertThat(booking.payment()).isEmpty();
    assertThat(booking.pullDomainEvents())
        .containsExactly(
            new BookingCancelled(BOOKING, USER, EVENT, CancelReason.USER_REQUEST, LATER));
  }

  @Test
  void should_open_a_refund_when_a_confirmed_booking_is_cancelled_by_the_user() {
    Booking booking = restored(BookingStatus.CONFIRMED);

    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(LATER));

    assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.payment())
        .hasValueSatisfying(payment -> {
          assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
          assertThat(payment.refundReason()).contains(RefundReason.BOOKING_CANCELLED);
        });
  }

  @Test
  void should_open_a_refund_with_the_event_reason_when_the_event_is_cancelled() {
    Booking booking = restored(BookingStatus.CONFIRMED);

    booking.cancel(CancelReason.EVENT_CANCELLED, EVENT_STARTS, at(LATER));

    assertThat(booking.payment().orElseThrow().refundReason())
        .contains(RefundReason.EVENT_CANCELLED);
  }

  @Test
  void should_refuse_a_user_cancellation_of_a_confirmed_booking_once_the_event_started() {
    Booking booking = restored(BookingStatus.CONFIRMED);

    assertThatThrownBy(
            () -> booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(EVENT_STARTS)))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.CANCEL_AFTER_START));
    assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.payment().orElseThrow().status()).isEqualTo(PaymentStatus.CAPTURED);
  }

  @Test
  void should_still_cancel_with_the_event_after_it_started() {
    Booking booking = restored(BookingStatus.CONFIRMED);

    booking.cancel(CancelReason.EVENT_CANCELLED, EVENT_STARTS, at(EVENT_STARTS.plusSeconds(60)));

    assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void should_cancel_a_pending_booking_even_after_the_event_started() {
    Booking booking = restored(BookingStatus.PENDING);

    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(EVENT_STARTS.plusSeconds(60)));

    assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void should_reject_missing_arguments() {
    Booking booking = pendingBooking();

    assertThatThrownBy(() -> booking.cancel(null, EVENT_STARTS, at(LATER)))
        .isInstanceOf(InvalidValueException.class);
    assertThatThrownBy(() -> booking.cancel(CancelReason.USER_REQUEST, null, at(LATER)))
        .isInstanceOf(InvalidValueException.class);
  }
}
