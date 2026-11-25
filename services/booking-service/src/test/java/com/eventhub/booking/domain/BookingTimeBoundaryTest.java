package com.eventhub.booking.domain;

import static com.eventhub.booking.domain.BookingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.Booking.CaptureOutcome;
import com.eventhub.booking.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The hold is half-open: payable while {@code now < expiresAt}, expirable once {@code now >=
 * expiresAt}.
 */
class BookingTimeBoundaryTest {

  private static final Duration TICK = Duration.ofNanos(1);

  private final MutableClock clock = new MutableClock(NOW);

  @Test
  void should_allow_paying_one_tick_before_the_hold_ends() {
    Booking booking = pendingBooking();
    clock.set(EXPIRES_AT.minus(TICK));

    Payment payment = booking.beginPayment(PAYMENT, clock);

    assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATED);
  }

  @Test
  void should_refuse_paying_exactly_when_the_hold_ends() {
    Booking booking = pendingBooking();
    clock.set(EXPIRES_AT);

    assertThatThrownBy(() -> booking.beginPayment(PAYMENT, clock))
        .isInstanceOf(InvalidStateException.class);
    assertThat(booking.payment()).isEmpty();
  }

  @Test
  void should_refuse_paying_after_the_hold_ended() {
    Booking booking = pendingBooking();
    clock.set(EXPIRES_AT.plusSeconds(1));

    assertThatThrownBy(() -> booking.beginPayment(PAYMENT, clock))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void should_refuse_to_expire_one_tick_before_the_hold_ends() {
    Booking booking = pendingBooking();
    clock.set(EXPIRES_AT.minus(TICK));

    assertThatThrownBy(() -> booking.expire(clock)).isInstanceOf(InvalidStateException.class);
    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void should_expire_exactly_when_the_hold_ends() {
    Booking booking = pendingBooking();
    booking.pullDomainEvents();
    clock.set(EXPIRES_AT);

    booking.expire(clock);

    assertThat(booking.status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(booking.expiredAt()).contains(EXPIRES_AT);
    assertThat(booking.pullDomainEvents())
        .containsExactly(new BookingExpired(BOOKING, USER, EVENT, EXPIRES_AT));
  }

  @Test
  void should_report_the_hold_as_expirable_only_from_expires_at() {
    Booking booking = pendingBooking();

    clock.set(EXPIRES_AT.minus(TICK));
    assertThat(booking.isExpirable(clock)).isFalse();
    clock.set(EXPIRES_AT);
    assertThat(booking.isExpirable(clock)).isTrue();
  }

  @Test
  void should_confirm_a_charge_captured_one_tick_before_the_hold_ends() {
    Booking booking = pendingBooking();
    booking.beginPayment(PAYMENT, clock);
    clock.set(EXPIRES_AT.minus(TICK));

    CaptureOutcome outcome = booking.capturePayment(clock);

    assertThat(outcome).isEqualTo(CaptureOutcome.CONFIRMED);
    assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
  }

  @Test
  void should_demand_a_refund_for_a_charge_captured_exactly_when_the_hold_ends() {
    Booking booking = pendingBooking();
    booking.beginPayment(PAYMENT, clock);
    clock.set(EXPIRES_AT);

    CaptureOutcome outcome = booking.capturePayment(clock);

    assertThat(outcome).isEqualTo(CaptureOutcome.REFUND_REQUIRED);
    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
    assertThat(booking.payment())
        .hasValueSatisfying(payment -> {
          assertThat(payment.status()).isEqualTo(PaymentStatus.REFUND_PENDING);
          assertThat(payment.refundReason()).contains(RefundReason.BOOKING_EXPIRED_DURING_PAYMENT);
        });
  }

  @Test
  void should_cancel_a_confirmed_booking_before_the_event_starts_but_not_at_its_start() {
    Booking before = restored(BookingStatus.CONFIRMED);
    clock.set(EVENT_STARTS.minus(TICK));
    before.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, clock);
    assertThat(before.status()).isEqualTo(BookingStatus.CANCELLED);

    Booking atStart = restored(BookingStatus.CONFIRMED);
    clock.set(EVENT_STARTS);
    assertThatThrownBy(() -> atStart.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, clock))
        .isInstanceOf(BusinessRuleViolationException.class);
  }
}
