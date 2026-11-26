package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.exception.PaymentDeclinedException;
import com.eventhub.booking.application.exception.PaymentRefundPendingException;
import com.eventhub.booking.application.exception.PaymentRefundedException;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.FakePaymentGateway.Gate;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.application.port.out.StoredBooking;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingConfirmed;
import com.eventhub.booking.domain.BookingPaymentRefunded;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.InvalidStateException;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentInProgressException;
import com.eventhub.booking.domain.PaymentStatus;
import com.eventhub.booking.domain.RefundReason;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PayBookingServiceTest {

  private static final UUID BOOKING = uuid(1);
  private static final String APPROVED = "tok_approved";

  private final AppEnv env = new AppEnv().withOpenEvent();
  private final InstrumentedGateway gateway = new InstrumentedGateway(env);
  private final PayBookingService service = service(env.bookings);
  private final ExecutorService pool = Executors.newCachedThreadPool();

  private PayBookingService service(BookingRepository bookings) {
    return new PayBookingService(
        env.tx, bookings, env.publisher, env.ids, gateway, env.clock, env.metrics);
  }

  @AfterEach
  void shutDownAndCheckTheSagaRules() {
    pool.shutdownNow();
    assertThat(gateway.violations()).isEmpty();
  }

  private Booking pendingBooking() {
    Booking booking = newBooking(BOOKING, USER, env.clock, new RequestedItem(VIP_ZONE, 2));
    booking.pullDomainEvents();
    env.bookings.saveNew(booking, new IdempotencyRecord(USER, uuid(900), "fp"));
    env.inventory.reserve(EVENT, booking.requestedItems());
    return booking;
  }

  private static PayBookingCommand pay(String token) {
    return new PayBookingCommand(USER, BOOKING, token);
  }

  private Payment storedPayment() {
    return InstrumentedGateway.paymentOf(env, BOOKING);
  }

  private Throwable failureOf(Future<?> future) throws InterruptedException {
    try {
      future.get();
      return null;
    } catch (ExecutionException e) {
      return e.getCause();
    }
  }

  // ---- the three steps ----

  @Test
  void should_confirm_the_booking_and_capture_the_payment_when_the_gateway_approves() {
    pendingBooking();

    Booking result = service.pay(pay(APPROVED));

    assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(storedPayment().status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(storedPayment().amount()).isEqualTo(result.total());
    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
  }

  @Test
  void should_use_the_payment_id_as_the_gateway_key_and_publish_it_in_the_confirmation() {
    pendingBooking();

    service.pay(pay(APPROVED));

    UUID paymentId = storedPayment().id();
    assertThat(env.gateway.holds(paymentId)).isTrue();
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class))
        .singleElement()
        .satisfies(event -> assertThat(event.paymentId()).isEqualTo(paymentId));
  }

  @Test
  void should_keep_the_inventory_held_when_the_booking_is_confirmed() {
    pendingBooking();

    service.pay(pay(APPROVED));

    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_decline_and_keep_the_booking_pending_when_the_gateway_declines() {
    pendingBooking();

    assertThatThrownBy(() -> service.pay(pay("tok_declined")))
        .isInstanceOf(PaymentDeclinedException.class);

    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.PENDING);
    assertThat(storedPayment().status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(env.gateway.distinctCharges()).isZero();
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class)).isEmpty();
  }

  @Test
  void should_start_a_new_attempt_with_a_new_payment_id_when_paying_again_after_a_decline() {
    pendingBooking();
    assertThatThrownBy(() -> service.pay(pay("tok_declined")))
        .isInstanceOf(PaymentDeclinedException.class);
    UUID declined = storedPayment().id();

    Booking result = service.pay(pay(APPROVED));

    assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(storedPayment().id()).isNotEqualTo(declined);
    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
  }

  @Test
  void should_return_the_same_booking_without_charging_again_when_it_is_already_confirmed() {
    pendingBooking();
    service.pay(pay(APPROVED));
    int callsBefore = env.gateway.chargeCalls();

    Booking again = service.pay(pay(APPROVED));

    assertThat(again.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.gateway.chargeCalls()).isEqualTo(callsBefore);
    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class)).hasSize(1);
  }

  // ---- who and when ----

  @Test
  void should_answer_not_found_without_charging_when_the_booking_is_someone_elses() {
    pendingBooking();

    assertThatThrownBy(() -> service.pay(new PayBookingCommand(OTHER_USER, BOOKING, APPROVED)))
        .isInstanceOf(NotFoundException.class);
    assertThat(env.gateway.chargeCalls()).isZero();
  }

  @Test
  void should_answer_not_found_when_the_booking_does_not_exist() {
    assertThatThrownBy(() -> service.pay(pay(APPROVED))).isInstanceOf(NotFoundException.class);
  }

  @Test
  void should_refuse_without_charging_when_the_hold_has_ended_but_the_job_has_not_run_yet() {
    pendingBooking();
    env.clock.set(EXPIRES_AT);

    assertThatThrownBy(() -> service.pay(pay(APPROVED))).isInstanceOf(InvalidStateException.class);
    assertThat(env.gateway.chargeCalls()).isZero();
  }

  @Test
  void should_refuse_without_charging_when_the_booking_expired() {
    Booking booking = pendingBooking();
    env.clock.set(EXPIRES_AT);
    booking.expire(env.clock);
    env.bookings.save(booking);

    assertThatThrownBy(() -> service.pay(pay(APPROVED))).isInstanceOf(InvalidStateException.class);
    assertThat(env.gateway.chargeCalls()).isZero();
  }

  @Test
  void should_refuse_without_charging_when_the_booking_was_cancelled() {
    Booking booking = pendingBooking();
    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, env.clock);
    env.bookings.save(booking);

    assertThatThrownBy(() -> service.pay(pay(APPROVED))).isInstanceOf(InvalidStateException.class);
    assertThat(env.gateway.chargeCalls()).isZero();
  }

  // ---- unknown outcome of the charge ----

  @Test
  void should_answer_payment_in_progress_and_leave_it_initiated_when_the_charge_times_out() {
    pendingBooking();
    env.gateway.failNextChargesBeforeProcessing(1);

    assertThatThrownBy(() -> service.pay(pay(APPROVED)))
        .isInstanceOfSatisfying(
            PaymentInProgressException.class,
            e -> assertThat(e.paymentId()).isEqualTo(storedPayment().id()));

    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.PENDING);
    assertThat(storedPayment().status()).isEqualTo(PaymentStatus.INITIATED);
    assertThat(env.gateway.distinctCharges()).isZero();
  }

  @Test
  void should_leave_it_initiated_for_the_reconciler_when_the_answer_is_lost_after_charging() {
    pendingBooking();
    env.gateway.failNextChargesAfterProcessing(1);

    assertThatThrownBy(() -> service.pay(pay(APPROVED)))
        .isInstanceOf(PaymentInProgressException.class);

    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.PENDING);
    assertThat(storedPayment().status()).isEqualTo(PaymentStatus.INITIATED);
  }

  @Test
  void should_keep_refusing_a_new_attempt_while_the_unknown_charge_is_unresolved() {
    pendingBooking();
    env.gateway.failNextChargesBeforeProcessing(1);
    assertThatThrownBy(() -> service.pay(pay(APPROVED)))
        .isInstanceOf(PaymentInProgressException.class);

    assertThatThrownBy(() -> service.pay(pay(APPROVED)))
        .isInstanceOf(PaymentInProgressException.class);

    assertThat(env.gateway.chargeCalls()).isEqualTo(1);
  }

  // ---- concurrency around the charge ----

  @Test
  void should_answer_payment_in_progress_to_a_second_pay_while_the_first_is_charging()
      throws Exception {
    pendingBooking();
    Gate gate = env.gateway.holdCharges();
    Future<Booking> first = pool.submit(() -> service.pay(pay(APPROVED)));
    gate.awaitArrival();

    assertThatThrownBy(() -> service.pay(pay(APPROVED)))
        .isInstanceOf(PaymentInProgressException.class);
    gate.open();

    assertThat(first.get().status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
    assertThat(env.gateway.chargeCalls()).isEqualTo(1);
  }

  @Test
  void should_refund_and_answer_payment_refunded_when_the_hold_ends_during_the_charge()
      throws Exception {
    pendingBooking();

    Throwable failure = payWhile(env -> env.clock.set(EXPIRES_AT));

    assertThat(failure).isInstanceOf(PaymentRefundedException.class);
    assertRefunded(RefundReason.BOOKING_EXPIRED_DURING_PAYMENT);
    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void should_refund_with_the_expired_reason_when_the_job_expired_it_during_the_charge()
      throws Exception {
    pendingBooking();

    Throwable failure =
        payWhile(env -> {
          env.clock.set(EXPIRES_AT);
          Booking booking = env.booking(BOOKING);
          booking.expire(env.clock);
          env.bookings.save(booking);
        });

    assertThat(failure).isInstanceOf(PaymentRefundedException.class);
    assertRefunded(RefundReason.BOOKING_EXPIRED_DURING_PAYMENT);
    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.EXPIRED);
  }

  @Test
  void should_refund_with_the_cancelled_reason_when_the_user_cancelled_during_the_charge()
      throws Exception {
    pendingBooking();

    Throwable failure = payWhile(env -> cancelBooking(env, CancelReason.USER_REQUEST));

    assertThat(failure).isInstanceOf(PaymentRefundedException.class);
    assertRefunded(RefundReason.BOOKING_CANCELLED);
    assertThat(env.booking(BOOKING).status()).isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void should_refund_with_the_event_reason_when_the_event_was_cancelled_during_the_charge()
      throws Exception {
    pendingBooking();

    Throwable failure = payWhile(env -> cancelBooking(env, CancelReason.EVENT_CANCELLED));

    assertThat(failure).isInstanceOf(PaymentRefundedException.class);
    assertRefunded(RefundReason.EVENT_CANCELLED);
  }

  @Test
  void should_leave_the_refund_pending_for_the_reconciler_when_the_refund_fails() throws Exception {
    pendingBooking();
    env.gateway.failAllRefunds(true);

    Throwable failure = payWhile(env -> env.clock.set(EXPIRES_AT));

    assertThat(failure).isInstanceOf(PaymentRefundPendingException.class);
    assertThat(storedPayment().status()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(storedPayment().refundReason())
        .contains(RefundReason.BOOKING_EXPIRED_DURING_PAYMENT);
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class)).isEmpty();
    assertThat(env.gateway.holds(storedPayment().id())).isTrue();
    assertThat(env.metrics.immediateRefundFailures()).containsExactly(BOOKING);
  }

  @Test
  void should_answer_refund_pending_when_the_refund_cannot_be_recorded_after_the_gateway_paid_back()
      throws Exception {
    pendingBooking();
    PayBookingService recordingFails =
        new PayBookingService(
            env.tx,
            env.bookings,
            new FailingRefundEventsPublisher(env.publisher),
            env.ids,
            gateway,
            env.clock,
            env.metrics);
    Gate gate = env.gateway.holdCharges();
    Future<Booking> paying = pool.submit(() -> recordingFails.pay(pay(APPROVED)));
    gate.awaitArrival();
    env.clock.set(EXPIRES_AT);
    gate.open();

    Throwable failure = failureOf(paying);

    assertThat(failure).isInstanceOf(PaymentRefundPendingException.class);
    assertThat(storedPayment().status())
        .as("the money went back at the gateway, but it was not recorded")
        .isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(env.gateway.wasRefunded(storedPayment().id())).isTrue();
    assertThat(env.metrics.immediateRefundFailures()).containsExactly(BOOKING);
  }

  @Test
  void should_not_count_a_failed_immediate_refund_when_the_refund_completes() throws Exception {
    pendingBooking();

    Throwable failure = payWhile(env -> env.clock.set(EXPIRES_AT));

    assertThat(failure).isInstanceOf(PaymentRefundedException.class);
    assertThat(env.metrics.immediateRefundFailures()).isEmpty();
  }

  @Test
  void should_retry_the_step_when_the_booking_changed_between_reading_and_saving() {
    pendingBooking();
    PayBookingService flaky = service(new ConflictOnSecondSave(env.bookings));

    Booking result = flaky.pay(pay(APPROVED));

    assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.gateway.distinctCharges()).isEqualTo(1);
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class)).hasSize(1);
  }

  // ---- helpers ----

  private void cancelBooking(AppEnv env, CancelReason reason) {
    Booking booking = env.booking(BOOKING);
    booking.cancel(reason, EVENT_STARTS, env.clock);
    env.bookings.save(booking);
  }

  /**
   * Pays, and while the charge is in flight lets the test change the world, then lets it finish.
   */
  private Throwable payWhile(Consumer<AppEnv> interference) throws Exception {
    Gate gate = env.gateway.holdCharges();
    Future<Booking> paying = pool.submit(() -> service.pay(pay(APPROVED)));
    gate.awaitArrival();
    interference.accept(env);
    gate.open();
    return failureOf(paying);
  }

  private void assertRefunded(RefundReason reason) {
    Payment payment = storedPayment();
    assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(payment.refundReason()).contains(reason);
    assertThat(env.gateway.wasRefunded(payment.id())).isTrue();
    assertThat(env.gateway.holds(payment.id())).isFalse();
    assertThat(env.publisher.eventsOfType(BookingPaymentRefunded.class))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.reason()).isEqualTo(reason);
          assertThat(event.paymentId()).isEqualTo(payment.id());
          assertThat(event.amount()).isEqualTo(payment.amount());
        });
    assertThat(env.publisher.eventsOfType(BookingConfirmed.class)).isEmpty();
  }

  /** Fails the second save once, as if someone else had saved the booking in between. */
  private static final class ConflictOnSecondSave implements BookingRepository {
    private final BookingRepository delegate;
    private int saves;

    ConflictOnSecondSave(BookingRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public Booking save(Booking booking) {
      saves++;
      if (saves == 2) {
        throw new ConcurrencyConflictException(booking.id());
      }
      return delegate.save(booking);
    }

    @Override
    public Booking saveNew(Booking booking, IdempotencyRecord idempotency) {
      return delegate.saveNew(booking, idempotency);
    }

    @Override
    public Optional<Booking> findById(UUID id) {
      return delegate.findById(id);
    }

    @Override
    public Optional<StoredBooking> findByIdempotencyKey(UUID userId, UUID key) {
      return delegate.findByIdempotencyKey(userId, key);
    }

    @Override
    public com.eventhub.booking.application.Page<Booking> findByUser(
        UUID userId,
        Optional<BookingStatus> status,
        com.eventhub.booking.application.PageQuery page) {
      return delegate.findByUser(userId, status, page);
    }

    @Override
    public List<Booking> findExpirable(Instant now, int limit) {
      return delegate.findExpirable(now, limit);
    }

    @Override
    public List<UUID> findActiveIdsByEvent(UUID eventId, int limit) {
      return delegate.findActiveIdsByEvent(eventId, limit);
    }
  }
}
