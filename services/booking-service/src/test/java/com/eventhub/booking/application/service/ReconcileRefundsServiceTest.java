package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.in.ReconcileReport;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingConfirmed;
import com.eventhub.booking.domain.BookingPaymentRefunded;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentInProgressException;
import com.eventhub.booking.domain.PaymentStatus;
import com.eventhub.booking.domain.RefundReason;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconcileRefundsServiceTest {

  private static final Duration STALE = Duration.ofMinutes(5);
  private static final Duration INITIAL_BACKOFF = Duration.ofMinutes(1);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(4);

  private final AppEnv env = new AppEnv().withOpenEvent();
  private final PayBookingService pay =
      new PayBookingService(
          env.tx, env.bookings, env.publisher, env.ids, env.gateway, env.clock, env.metrics);
  private final ReconcileRefundsService reconciler =
      new ReconcileRefundsService(
          env.tx,
          env.bookings,
          env.reconciliation,
          env.publisher,
          env.gateway,
          env.clock,
          new ReconcilerPolicy(STALE, INITIAL_BACKOFF, MAX_BACKOFF, 10));

  private void pendingBooking(long id) {
    Booking booking = newBooking(uuid(id), USER, env.clock, new RequestedItem(VIP_ZONE, 2));
    booking.pullDomainEvents();
    env.bookings.saveNew(booking, new IdempotencyRecord(USER, uuid(900 + id), "fp" + id));
    env.inventory.reserve(EVENT, booking.requestedItems());
  }

  private Payment payment(long bookingId) {
    return env.booking(uuid(bookingId)).payment().orElseThrow();
  }

  /** A confirmed booking whose event gets cancelled: the cascade leaves the refund pending. */
  private void confirmedThenEventCancelled(long id) {
    pendingBooking(id);
    pay.pay(new PayBookingCommand(USER, uuid(id), "tok_approved"));
    new ApplyEventCancelledService(
            env.tx,
            env.projections,
            env.bookings,
            env.inventory,
            env.processed,
            env.publisher,
            env.clock,
            10)
        .apply(new EventCancelledMessage(uuid(8000 + id), EVENT, env.clock.instant()));
  }

  /** A pay whose answer was lost: the payment stays INITIATED, here after the charge happened. */
  private void orphanAfterCharging(long id, String token) {
    pendingBooking(id);
    env.gateway.failNextChargesAfterProcessing(1);
    assertThatThrownBy(() -> pay.pay(new PayBookingCommand(USER, uuid(id), token)))
        .isInstanceOf(PaymentInProgressException.class);
  }

  // ---- refunds ----

  @Test
  void should_complete_a_refund_left_pending_by_the_cancellation_cascade() {
    confirmedThenEventCancelled(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.REFUND_PENDING);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.refundsCompleted()).isEqualTo(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(env.gateway.holds(payment(1).id())).isFalse();
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class))
        .singleElement()
        .satisfies(event -> assertThat(event.reason()).isEqualTo(RefundReason.EVENT_CANCELLED));
  }

  @Test
  void should_refund_each_payment_once_however_many_times_it_runs() {
    confirmedThenEventCancelled(1);

    reconciler.reconcile();
    ReconcileReport second = reconciler.reconcile();

    assertThat(second.refundsCompleted()).isZero();
    assertThat(env.gateway.refundCalls()).isEqualTo(1);
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class)).hasSize(1);
  }

  @Test
  void should_keep_the_refund_pending_and_back_off_when_the_gateway_still_fails() {
    confirmedThenEventCancelled(1);
    env.gateway.failAllRefunds(true);

    ReconcileReport first = reconciler.reconcile();

    assertThat(first.refundsFailed()).isEqualTo(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(env.reconciliation.failedAttempts(payment(1).id())).isEqualTo(1);

    int callsAfterFirst = env.gateway.refundCalls();
    env.clock.advance(INITIAL_BACKOFF.minusNanos(1));
    reconciler.reconcile();
    assertThat(env.gateway.refundCalls()).isEqualTo(callsAfterFirst);

    env.clock.advance(Duration.ofNanos(1));
    reconciler.reconcile();
    assertThat(env.gateway.refundCalls()).isEqualTo(callsAfterFirst + 1);
  }

  @Test
  void should_double_the_wait_after_each_failure_up_to_the_cap() {
    confirmedThenEventCancelled(1);
    env.gateway.failAllRefunds(true);
    UUID paymentId = payment(1).id();
    Duration[] expectedWaits = {
      INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2), MAX_BACKOFF, MAX_BACKOFF
    };

    for (Duration wait : expectedWaits) {
      int before = env.gateway.refundCalls();
      reconciler.reconcile();
      assertThat(env.gateway.refundCalls()).isEqualTo(before + 1);
      env.clock.advance(wait.minusNanos(1));
      reconciler.reconcile();
      assertThat(env.gateway.refundCalls())
          .as("still backing off for %s", wait)
          .isEqualTo(before + 1);
      env.clock.advance(Duration.ofNanos(1));
    }
    assertThat(env.reconciliation.failedAttempts(paymentId)).isEqualTo(4);
  }

  @Test
  void should_finish_the_refund_when_the_gateway_recovers() {
    confirmedThenEventCancelled(1);
    env.gateway.failAllRefunds(true);
    reconciler.reconcile();
    env.gateway.failAllRefunds(false);
    env.clock.advance(INITIAL_BACKOFF);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.refundsCompleted()).isEqualTo(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.REFUNDED);
  }

  // ---- orphan charges ----

  @Test
  void should_leave_an_initiated_payment_alone_until_it_is_old_enough() {
    orphanAfterCharging(1, "tok_approved");
    env.clock.advance(STALE.minusNanos(1));

    ReconcileReport report = reconciler.reconcile();

    assertThat(report).isEqualTo(new ReconcileReport(0, 0, 0, 0, 0, 0));
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.INITIATED);
  }

  @Test
  void should_confirm_the_booking_when_the_repeated_charge_is_approved_and_it_is_still_valid() {
    orphanAfterCharging(1, "tok_approved");
    env.clock.advance(STALE);
    int callsBefore = env.gateway.chargeCalls();

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansConfirmed()).isEqualTo(1);
    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(env.gateway.chargeCalls()).isEqualTo(callsBefore + 1);
    assertThat(env.gateway.distinctCharges())
        .as("repeating the charge never charges twice")
        .isEqualTo(1);
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class)).hasSize(1);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_charge_now_when_the_original_charge_never_reached_the_gateway() {
    pendingBooking(1);
    env.gateway.failNextChargesBeforeProcessing(1);
    assertThatThrownBy(() -> pay.pay(new PayBookingCommand(USER, uuid(1), "tok_approved")))
        .isInstanceOf(PaymentInProgressException.class);
    assertThat(env.gateway.distinctCharges()).isZero();
    env.clock.advance(STALE);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansConfirmed()).isEqualTo(1);
    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.CONFIRMED);
  }

  @Test
  void should_refund_when_the_booking_expired_before_the_orphan_charge_was_resolved() {
    orphanAfterCharging(1, "tok_approved");
    env.clock.set(EXPIRES_AT);
    new ExpireBookingsService(env.tx, env.bookings, env.inventory, env.publisher, env.clock)
        .expireDue(10);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansRefunded()).isEqualTo(1);
    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(payment(1).refundReason()).contains(RefundReason.BOOKING_EXPIRED_DURING_PAYMENT);
    assertThat(env.gateway.holds(payment(1).id()))
        .as("no money is kept for a lost booking")
        .isFalse();
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class)).isEmpty();
  }

  @Test
  void should_refund_when_the_hold_ended_even_if_the_job_has_not_expired_the_booking_yet() {
    orphanAfterCharging(1, "tok_approved");
    env.clock.set(EXPIRES_AT);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansRefunded()).isEqualTo(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void should_decline_the_payment_when_the_repeated_charge_is_declined() {
    pendingBooking(1);
    env.gateway.failNextChargesBeforeProcessing(1);
    assertThatThrownBy(() -> pay.pay(new PayBookingCommand(USER, uuid(1), "tok_declined")))
        .isInstanceOf(PaymentInProgressException.class);
    env.clock.advance(STALE);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansDeclined()).isEqualTo(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void should_let_the_customer_pay_again_once_the_orphan_was_declined() {
    pendingBooking(1);
    env.gateway.failNextChargesBeforeProcessing(1);
    assertThatThrownBy(() -> pay.pay(new PayBookingCommand(USER, uuid(1), "tok_declined")))
        .isInstanceOf(PaymentInProgressException.class);
    env.clock.advance(STALE);
    reconciler.reconcile();

    Booking retried = pay.pay(new PayBookingCommand(USER, uuid(1), "tok_approved"));

    assertThat(retried.status()).isEqualTo(BookingStatus.CONFIRMED);
  }

  @Test
  void should_back_off_and_leave_it_initiated_when_the_gateway_is_still_down() {
    orphanAfterCharging(1, "tok_approved");
    env.clock.advance(STALE);
    env.gateway.failNextChargesBeforeProcessing(1);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansUnresolved()).isEqualTo(1);
    assertThat(payment(1).status()).isEqualTo(PaymentStatus.INITIATED);
    int calls = env.gateway.chargeCalls();
    reconciler.reconcile();
    assertThat(env.gateway.chargeCalls()).as("not retried before its backoff").isEqualTo(calls);

    env.clock.advance(INITIAL_BACKOFF);
    assertThat(reconciler.reconcile().orphansConfirmed()).isEqualTo(1);
  }

  @Test
  void should_resolve_several_orphans_in_one_pass() {
    orphanAfterCharging(1, "tok_approved");
    orphanAfterCharging(2, "tok_approved");
    env.clock.advance(STALE);

    ReconcileReport report = reconciler.reconcile();

    assertThat(report.orphansConfirmed()).isEqualTo(2);
    assertThat(env.gateway.distinctCharges()).isEqualTo(2);
  }

  @Test
  void should_report_nothing_to_do_when_every_payment_is_settled() {
    pendingBooking(1);
    pay.pay(new PayBookingCommand(USER, uuid(1), "tok_approved"));

    assertThat(reconciler.reconcile()).isEqualTo(new ReconcileReport(0, 0, 0, 0, 0, 0));
    assertThat(env.reconciliation.countRefundPending()).isZero();
    assertThat(env.reconciliation.countStaleInitiated(env.clock.instant())).isZero();
  }

  @Test
  void should_expose_how_many_payments_are_waiting_for_the_gauges() {
    confirmedThenEventCancelled(1);
    orphanAfterCharging(2, "tok_approved");
    env.clock.advance(STALE);

    assertThat(env.reconciliation.countRefundPending()).isEqualTo(1);
    assertThat(env.reconciliation.countStaleInitiated(env.clock.instant().minus(STALE)))
        .isEqualTo(1);
  }
}
