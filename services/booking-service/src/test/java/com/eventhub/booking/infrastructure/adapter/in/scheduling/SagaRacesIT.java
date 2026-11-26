package com.eventhub.booking.infrastructure.adapter.in.scheduling;

import static com.eventhub.booking.application.AppFixtures.*;
import static com.eventhub.booking.support.SagaAssertions.assertConsistent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.exception.PaymentDeclinedException;
import com.eventhub.booking.application.exception.PaymentRefundPendingException;
import com.eventhub.booking.application.exception.PaymentRefundedException;
import com.eventhub.booking.application.port.in.ApplyEventCancelledUseCase;
import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.application.port.out.FakePaymentGateway.Gate;
import com.eventhub.booking.application.service.PayBookingService;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.Concurrently;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import com.eventhub.booking.support.StepGatedTransactionRunner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The races of the payment saga against the real database and the real services, ten times each.
 * The gateway is controlled with latches and the transaction runner of one pay can be held or
 * killed between its steps, so every interleaving is forced, never hoped for, and nothing sleeps.
 * After each race {@code assertConsistent} checks the one guarantee: a confirmed booking with its
 * money captured, or a dead booking with its money back and its tickets released.
 */
@PersistenceIT
class SagaRacesIT {

  private static final int REPEATS = 10;

  @Autowired private ApplicationContext context;
  @Autowired private FakePaymentGateway gateway;
  @Autowired private ReconcileRefundsUseCase reconciler;
  @Autowired private ApplyEventCancelledUseCase eventCancelled;
  private RealApp app;
  private ExecutorService pool;

  @BeforeEach
  void wire() {
    gateway.reset();
    app = new RealApp(context);
    app.clock.set(NOW);
    app.openEvent(10, 10);
    pool = Executors.newCachedThreadPool();
  }

  @AfterEach
  void stopThreads() {
    pool.shutdownNow();
  }

  // ---------------------------------------------------------------- helpers

  private UUID pendingBooking() {
    return pendingBooking(USER, 900);
  }

  private UUID pendingBooking(UUID user, long key) {
    return app.create()
        .create(
            new CreateBookingCommand(
                user, uuid(key), EVENT, List.of(new RequestedItem(VIP_ZONE, 1))))
        .booking()
        .id();
  }

  private static PayBookingCommand pay(UUID booking, String token) {
    return new PayBookingCommand(USER, booking, token);
  }

  private Future<Booking> payAsync(PayBookingService service, UUID booking, String token) {
    return pool.submit(() -> service.pay(pay(booking, token)));
  }

  private static Throwable failureOf(Future<?> future) throws Exception {
    try {
      future.get(30, TimeUnit.SECONDS);
      return null;
    } catch (ExecutionException e) {
      return e.getCause();
    }
  }

  private String paymentStatus(UUID booking) {
    return app.jdbc.queryForObject(
        "SELECT status FROM payment WHERE booking_id = ? ORDER BY created_at DESC LIMIT 1",
        String.class,
        booking);
  }

  private String bookingStatus(UUID booking) {
    return app.jdbc.queryForObject(
        "SELECT status FROM booking WHERE id = ?", String.class, booking);
  }

  private void pastTheStaleThreshold() {
    app.clock.advance(Duration.ofMinutes(3));
  }

  // ---------------------------------------------------------------- the races

  /** (1) The charge is approved just as the hold ends and the job expires the booking. */
  @RepeatedTest(REPEATS)
  void should_refund_and_leave_the_booking_expired_when_the_charge_is_approved_as_it_expires()
      throws Exception {
    UUID booking = pendingBooking();
    Gate gate = gateway.holdCharges();
    Future<Booking> paying = payAsync(app.pay(gateway), booking, "tok_approved");
    gate.awaitArrival();
    app.clock.set(EXPIRES_AT);
    assertThat(app.expire().expireDue(10)).isEqualTo(1);
    gate.open();

    assertThat(failureOf(paying)).isInstanceOf(PaymentRefundedException.class);

    assertThat(bookingStatus(booking)).isEqualTo("EXPIRED");
    assertThat(paymentStatus(booking)).isEqualTo("REFUNDED");
    assertThat(app.reserved(VIP_ZONE)).isZero();
    assertConsistent(app, gateway, true);
  }

  /** (2) The user cancels while the charge is in flight. */
  @RepeatedTest(REPEATS)
  void should_refund_when_the_user_cancels_during_the_charge() throws Exception {
    UUID booking = pendingBooking();
    Gate gate = gateway.holdCharges();
    Future<Booking> paying = payAsync(app.pay(gateway), booking, "tok_approved");
    gate.awaitArrival();
    app.cancel(gateway).cancel(USER, booking);
    gate.open();

    assertThat(failureOf(paying)).isInstanceOf(PaymentRefundedException.class);

    assertThat(bookingStatus(booking)).isEqualTo("CANCELLED");
    assertThat(paymentStatus(booking)).isEqualTo("REFUNDED");
    assertConsistent(app, gateway, true);
  }

  /** (3) The event is cancelled while the charge is in flight. */
  @RepeatedTest(REPEATS)
  void should_refund_when_the_event_is_cancelled_during_the_charge() throws Exception {
    UUID booking = pendingBooking();
    Gate gate = gateway.holdCharges();
    Future<Booking> paying = payAsync(app.pay(gateway), booking, "tok_approved");
    gate.awaitArrival();
    eventCancelled.apply(new EventCancelledMessage(uuid(7000), EVENT, NOW.plusSeconds(30)));
    gate.open();

    assertThat(failureOf(paying)).isInstanceOf(PaymentRefundedException.class);

    assertThat(bookingStatus(booking)).isEqualTo("CANCELLED");
    assertThat(
            app.count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'BookingPaymentRefunded'"
                    + " AND payload::text LIKE '%EVENT_CANCELLED%'"))
        .isEqualTo(1);
    assertConsistent(app, gateway, true);
  }

  /** (4) A declined card leaves the booking payable; the retry is a new attempt and confirms. */
  @RepeatedTest(REPEATS)
  void should_confirm_with_a_new_attempt_after_a_declined_one() {
    UUID booking = pendingBooking();

    assertThatThrownBy(() -> app.pay(gateway).pay(pay(booking, "tok_declined")))
        .isInstanceOf(PaymentDeclinedException.class);
    assertThat(bookingStatus(booking)).isEqualTo("PENDING");
    assertConsistent(app, gateway, true);

    app.pay(gateway).pay(pay(booking, "tok_approved"));

    assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
    assertThat(app.count("SELECT count(*) FROM payment WHERE booking_id = '" + booking + "'"))
        .as("the declined attempt and the approved one")
        .isEqualTo(2);
    assertThat(gateway.distinctCharges()).isEqualTo(1);
    assertConsistent(app, gateway, true);
  }

  /** (5) Paying again, in sequence, never charges again. */
  @RepeatedTest(REPEATS)
  void should_charge_once_when_the_same_booking_is_paid_twice_in_sequence() {
    UUID booking = pendingBooking();

    Booking first = app.pay(gateway).pay(pay(booking, "tok_approved"));
    Booking second = app.pay(gateway).pay(pay(booking, "tok_approved"));

    assertThat(first.status()).isEqualTo(second.status());
    assertThat(gateway.chargeCalls()).isEqualTo(1);
    assertThat(gateway.distinctCharges()).isEqualTo(1);
    assertConsistent(app, gateway, true);
  }

  /** (6) The refund fails: REFUND_PENDING holds the money until the reconciler pays it back. */
  @RepeatedTest(REPEATS)
  void should_leave_the_refund_pending_and_let_the_reconciler_finish_it() throws Exception {
    UUID booking = pendingBooking();
    gateway.failAllRefunds(true);
    Gate gate = gateway.holdCharges();
    Future<Booking> paying = payAsync(app.pay(gateway), booking, "tok_approved");
    gate.awaitArrival();
    app.clock.set(EXPIRES_AT);
    app.expire().expireDue(10);
    gate.open();

    assertThat(failureOf(paying)).isInstanceOf(PaymentRefundPendingException.class);

    assertThat(paymentStatus(booking)).isEqualTo("REFUND_PENDING");
    assertThat(app.reserved(VIP_ZONE)).as("tickets are free while the refund waits").isZero();
    assertConsistent(app, gateway, false);

    gateway.failAllRefunds(false);
    app.clock.advance(Duration.ofMinutes(15));
    assertThat(reconciler.reconcile().refundsCompleted()).isEqualTo(1);

    assertThat(paymentStatus(booking)).isEqualTo("REFUNDED");
    assertConsistent(app, gateway, true);
  }

  /** (7a) The process dies after an approved charge; the reconciler confirms a live booking. */
  @RepeatedTest(REPEATS)
  void should_confirm_the_orphan_charge_when_the_booking_is_still_payable() throws Exception {
    UUID booking = pendingBooking();
    crashedAfterCharging(booking);
    pastTheStaleThreshold();

    var report = reconciler.reconcile();

    assertThat(report.orphansConfirmed()).isEqualTo(1);
    assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
    assertThat(gateway.distinctCharges()).isEqualTo(1);
    assertConsistent(app, gateway, true);
  }

  /** (7b) The same crash, but the hold ended meanwhile: the reconciler gives the money back. */
  @RepeatedTest(REPEATS)
  void should_refund_the_orphan_charge_when_the_booking_expired_meanwhile() throws Exception {
    UUID booking = pendingBooking();
    crashedAfterCharging(booking);
    app.clock.set(EXPIRES_AT);
    app.expire().expireDue(10);

    var report = reconciler.reconcile();

    assertThat(report.orphansRefunded()).isEqualTo(1);
    assertThat(bookingStatus(booking)).isEqualTo("EXPIRED");
    assertThat(paymentStatus(booking)).isEqualTo("REFUNDED");
    assertConsistent(app, gateway, true);
  }

  /** (8) The reconciler settles a payment while its own pay is held right before step 3. */
  @RepeatedTest(REPEATS)
  void should_confirm_once_when_the_reconciler_settles_the_payment_before_the_pay_does()
      throws Exception {
    UUID booking = pendingBooking();
    StepGatedTransactionRunner held = StepGatedTransactionRunner.holdingTheSettleStep(app.tx);
    Future<Booking> paying = payAsync(payWith(held), booking, "tok_approved");
    held.awaitArrival();
    pastTheStaleThreshold();

    var report = reconciler.reconcile();
    assertThat(report.orphansConfirmed()).isEqualTo(1);
    held.release();

    assertThat(failureOf(paying)).as("the pay finds its payment already settled").isNull();
    assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
    assertThat(gateway.distinctCharges()).isEqualTo(1);
    assertConsistent(app, gateway, true);
  }

  /** (9) The job expires the booking that holds the last ticket while someone else asks for it. */
  @RepeatedTest(REPEATS)
  void should_never_oversell_or_release_twice_when_an_expiration_meets_a_create() throws Exception {
    app.jdbc.update("DELETE FROM zone_inventory");
    app.jdbc.update("DELETE FROM projected_zone");
    app.jdbc.update("DELETE FROM event_projection");
    app.openEvent(1, 1);
    UUID holder = pendingBooking(USER, 900);
    app.clock.set(EXPIRES_AT);

    var outcomes =
        Concurrently.run(
            2,
            i ->
                () ->
                    i == 0
                        ? (Object) app.expire().expireDue(10)
                        : (Object)
                            app.create()
                                .create(
                                    new CreateBookingCommand(
                                        OTHER_USER,
                                        uuid(901),
                                        EVENT,
                                        List.of(new RequestedItem(VIP_ZONE, 1)))));

    assertThat(outcomes.get(0).failure()).isNull();
    Throwable createFailure = outcomes.get(1).failure();
    assertThat(createFailure == null || createFailure instanceof InsufficientInventoryException)
        .as("a create either got the ticket or was told there is none: %s", createFailure)
        .isTrue();
    assertThat(bookingStatus(holder)).isEqualTo("EXPIRED");
    app.assertInventoryInvariant();
    if (createFailure != null) {
      assertThat(app.reserved(VIP_ZONE)).as("released once, nobody holds it").isZero();
      pendingBooking(OTHER_USER, 901);
      assertThat(app.reserved(VIP_ZONE)).isEqualTo(1);
    }
    assertConsistent(app, gateway, true);
  }

  // ---------------------------------------------------------------- scenario plumbing

  private PayBookingService payWith(
      com.eventhub.booking.application.port.out.TransactionRunner tx) {
    return new PayBookingService(
        tx, app.bookings, app.publisher, app.ids, gateway, app.clock, app.metrics);
  }

  /** A pay whose process dies after the gateway approved: the payment stays INITIATED. */
  private void crashedAfterCharging(UUID booking) throws Exception {
    StepGatedTransactionRunner dying = StepGatedTransactionRunner.crashingAtTheSettleStep(app.tx);
    Future<Booking> paying = payAsync(payWith(dying), booking, "tok_approved");

    assertThat(failureOf(paying)).isInstanceOf(StepGatedTransactionRunner.ProcessCrashed.class);

    assertThat(paymentStatus(booking)).isEqualTo("INITIATED");
    assertThat(gateway.distinctCharges()).as("the money did move").isEqualTo(1);
  }
}
