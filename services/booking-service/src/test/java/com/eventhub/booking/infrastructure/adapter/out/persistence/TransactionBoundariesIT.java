package com.eventhub.booking.infrastructure.adapter.out.persistence;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.exception.IdempotencyKeyReuseException;
import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.CreateBookingResult;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ChargeRequest;
import com.eventhub.booking.application.port.out.ChargeResult;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.RefundRequest;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.Concurrently;
import com.eventhub.booking.support.Concurrently.Outcome;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** What the real transaction runner promises, checked on PostgreSQL. */
@PersistenceIT
class TransactionBoundariesIT {

  @Autowired private ApplicationContext context;
  private RealApp app;

  @BeforeEach
  void wire() {
    app = new RealApp(context);
  }

  /** Records whether a transaction was open at the moment the gateway was called. */
  private static final class ProbeGateway implements PaymentGateway {
    final FakePaymentGateway delegate = new FakePaymentGateway();
    final List<Boolean> chargeInTransaction = new CopyOnWriteArrayList<>();
    final List<Boolean> refundInTransaction = new CopyOnWriteArrayList<>();

    @Override
    public ChargeResult charge(ChargeRequest request) {
      chargeInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
      return delegate.charge(request);
    }

    @Override
    public void refund(RefundRequest request) {
      refundInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
      delegate.refund(request);
    }
  }

  // ---- 1. commit-time failures become the same conflict ----

  @Test
  void should_report_a_failure_that_only_shows_at_commit_as_the_same_conflict_exception() {
    app.jdbc.execute(
        "CREATE TABLE IF NOT EXISTS runner_probe (n integer,"
            + " CONSTRAINT uq_runner_probe UNIQUE (n) DEFERRABLE INITIALLY DEFERRED)");
    app.jdbc.execute("TRUNCATE runner_probe");

    assertThatThrownBy(() ->
            app.tx.run(() -> {
              app.jdbc.update("INSERT INTO runner_probe VALUES (1)");
              app.jdbc.update("INSERT INTO runner_probe VALUES (1)");
            }))
        .isInstanceOf(ConcurrencyConflictException.class)
        .hasMessageContaining("uq_runner_probe");

    assertThat(app.count("SELECT count(*) FROM runner_probe")).isZero();
  }

  @Test
  void should_report_a_stale_save_inside_the_block_as_the_same_conflict_exception() {
    app.openEvent(20, 100);
    Booking created =
        app.create()
            .create(
                new CreateBookingCommand(
                    USER, uuid(900), EVENT, List.of(new RequestedItem(VIP_ZONE, 1))))
            .booking();
    Booking stale = app.bookings.findById(created.id()).orElseThrow();
    Booking winner = app.bookings.findById(created.id()).orElseThrow();
    winner.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW));
    app.bookings.save(winner);
    stale.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW));

    assertThatThrownBy(() -> app.tx.run(() -> app.bookings.save(stale)))
        .isInstanceOf(ConcurrencyConflictException.class);
  }

  // ---- 3. the gateway is never called inside a transaction ----

  @Test
  void should_call_the_gateway_outside_any_transaction_when_charging_and_refunding() {
    app.openEvent(20, 100);
    ProbeGateway gateway = new ProbeGateway();
    UUID bookingId =
        app.create()
            .create(
                new CreateBookingCommand(
                    USER, uuid(900), EVENT, List.of(new RequestedItem(VIP_ZONE, 2))))
            .booking()
            .id();

    app.pay(gateway).pay(new PayBookingCommand(USER, bookingId, "tok_approved"));
    app.cancel(gateway).cancel(USER, bookingId);

    assertThat(gateway.chargeInTransaction).containsExactly(false);
    assertThat(gateway.refundInTransaction).containsExactly(false);
    assertThat(app.count("SELECT count(*) FROM payment WHERE status = 'REFUNDED'")).isEqualTo(1);
  }

  @Test
  void should_see_the_probe_report_a_transaction_when_the_call_really_is_inside_one() {
    ProbeGateway gateway = new ProbeGateway();

    app.tx.run(
        () -> gateway.charge(new ChargeRequest(uuid(1), Money.of("1.00", "PEN"), "tok_approved")));

    assertThat(gateway.chargeInTransaction).containsExactly(true);
  }

  // ---- 2. concurrent Idempotency-Key ----

  @Test
  void should_create_one_booking_and_answer_the_rest_with_it_when_one_key_arrives_16_times()
      throws Exception {
    app.openEvent(20, 100);
    CreateBookingCommand same =
        new CreateBookingCommand(USER, uuid(900), EVENT, List.of(new RequestedItem(VIP_ZONE, 2)));

    List<Outcome<CreateBookingResult>> outcomes =
        Concurrently.run(16, i -> () -> app.create().create(same));

    assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).as("%s", o.failure()).isNull());
    assertThat(outcomes.stream().filter(o -> o.value().created()).count()).isEqualTo(1);
    assertThat(outcomes.stream().map(o -> o.value().booking().id()).distinct()).hasSize(1);
    assertThat(app.count("SELECT count(*) FROM booking")).isEqualTo(1);
    assertThat(app.reserved(VIP_ZONE)).as("held once, not 16 times").isEqualTo(2);
    app.assertInventoryInvariant();
  }

  @Test
  void should_refuse_the_reuse_of_a_key_with_another_body_on_the_real_database() {
    app.openEvent(20, 100);
    app.create()
        .create(
            new CreateBookingCommand(
                USER, uuid(900), EVENT, List.of(new RequestedItem(VIP_ZONE, 2))));

    assertThatThrownBy(() ->
            app.create()
                .create(
                    new CreateBookingCommand(
                        USER, uuid(900), EVENT, List.of(new RequestedItem(VIP_ZONE, 3)))))
        .isInstanceOf(IdempotencyKeyReuseException.class);
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(2);
  }

  // ---- FOR UPDATE SKIP LOCKED ----

  @Test
  void should_hand_two_concurrent_expirers_disjoint_bookings() throws Exception {
    app.openEvent(20, 100);
    for (int i = 0; i < 10; i++) {
      app.create()
          .create(
              new CreateBookingCommand(
                  uuid(10_000 + i),
                  uuid(20_000 + i),
                  EVENT,
                  List.of(new RequestedItem(VIP_ZONE, 1))));
    }
    app.clock.advance(HOLD.plusSeconds(1));
    CountDownLatch firstHoldsItsRows = new CountDownLatch(1);
    CountDownLatch secondIsDone = new CountDownLatch(1);

    List<Outcome<List<UUID>>> outcomes =
        Concurrently.run(
            2,
            n ->
                () ->
                    app.tx.execute(() -> {
                      if (n == 0) {
                        List<UUID> mine = idsOf(app.bookings, 5);
                        firstHoldsItsRows.countDown();
                        await(secondIsDone);
                        return mine;
                      }
                      await(firstHoldsItsRows);
                      List<UUID> mine = idsOf(app.bookings, 10);
                      secondIsDone.countDown();
                      return mine;
                    }));

    assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).as("%s", o.failure()).isNull());
    List<UUID> first = outcomes.get(0).value();
    List<UUID> second = outcomes.get(1).value();
    assertThat(first).hasSize(5);
    assertThat(second).as("the second one skips what the first one holds").hasSize(5);
    assertThat(first).doesNotContainAnyElementsOf(second);
  }

  private static List<UUID> idsOf(BookingRepository bookings, int limit) {
    return bookings.findExpirable(NOW.plus(HOLD).plusSeconds(1), limit).stream()
        .map(Booking::id)
        .toList();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch never opened");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
