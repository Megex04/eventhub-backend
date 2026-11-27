package com.eventhub.booking.infrastructure.adapter.out.persistence;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.CreateBookingResult;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.application.service.CancelBookingService;
import com.eventhub.booking.application.service.CreateBookingService;
import com.eventhub.booking.application.service.ExpireBookingsService;
import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.InvalidStateException;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.Concurrently;
import com.eventhub.booking.support.Concurrently.Outcome;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The inventory under real contention, against PostgreSQL. Whatever the interleaving, a zone is
 * never oversold and {@code reserved} always equals the tickets of the live bookings.
 */
@PersistenceIT
class InventoryConcurrencyIT {

  @Autowired private ApplicationContext context;
  private RealApp app;

  @BeforeEach
  void wire() {
    app = new RealApp(context);
  }

  private static RequestedItem vip(int quantity) {
    return new RequestedItem(VIP_ZONE, quantity);
  }

  private static RequestedItem general(int quantity) {
    return new RequestedItem(GENERAL_ZONE, quantity);
  }

  @Test
  void should_sell_exactly_twenty_tickets_when_200_buyers_compete_for_20() throws Exception {
    app.openEvent(20, 100);
    CreateBookingService create = app.create();

    List<Outcome<CreateBookingResult>> outcomes =
        Concurrently.run(
            200,
            i ->
                () ->
                    create.create(
                        new CreateBookingCommand(
                            uuid(10_000 + i), uuid(20_000 + i), EVENT, List.of(vip(1)))));

    long sold = outcomes.stream().filter(o -> o.value() != null && o.value().created()).count();
    long refused =
        outcomes.stream()
            .filter(o -> o.failure() instanceof InsufficientInventoryException)
            .count();
    assertThat(sold).isEqualTo(20);
    assertThat(refused).isEqualTo(180);
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(20);
    assertThat(app.count("SELECT count(*) FROM booking")).isEqualTo(20);
    app.assertInventoryInvariant();
  }

  @Test
  void should_never_exceed_the_capacity_when_quantities_and_zone_orders_are_mixed()
      throws Exception {
    app.openEvent(20, 30);
    CreateBookingService create = app.create();

    List<Outcome<CreateBookingResult>> outcomes =
        Concurrently.run(
            150,
            i ->
                () -> {
                  List<RequestedItem> items =
                      switch (i % 3) {
                        case 0 -> List.of(vip(1 + i % 6));
                        case 1 -> List.of(general(1 + i % 6));
                        default ->
                            (i / 3) % 2 == 0
                                ? List.of(vip(1 + i % 3), general(1 + i % 3))
                                : List.of(general(1 + i % 3), vip(1 + i % 3));
                      };
                  return create.create(
                      new CreateBookingCommand(uuid(10_000 + i), uuid(20_000 + i), EVENT, items));
                });

    assertThat(outcomes)
        .allSatisfy(outcome ->
            assertThat(
                    outcome.failure() == null
                        || outcome.failure() instanceof InsufficientInventoryException)
                .as("only sold-out refusals are acceptable: %s", outcome.failure())
                .isTrue());
    assertThat(outcomes.stream().filter(o -> o.value() != null).count()).isPositive();
    assertThat(outcomes.stream().filter(o -> o.failure() != null).count()).isPositive();
    assertThat(app.reserved(VIP_ZONE)).isLessThanOrEqualTo(20);
    assertThat(app.reserved(GENERAL_ZONE)).isLessThanOrEqualTo(30);
    app.assertInventoryInvariant();
  }

  @Test
  void should_keep_the_invariant_when_creating_cancelling_and_expiring_at_the_same_time()
      throws Exception {
    app.openEvent(20, 100);
    CreateBookingService create = app.create();
    CancelBookingService cancel = app.cancel(new FakePaymentGateway());
    ExpireBookingsService expire = app.expire();
    ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

    Concurrently.run(
        8,
        worker ->
            () -> {
              for (int round = 0; round < 40; round++) {
                try {
                  CreateBookingResult created =
                      create.create(
                          new CreateBookingCommand(
                              uuid(10_000 + worker),
                              uuid(1_000_000L + worker * 1_000L + round),
                              EVENT,
                              List.of(vip(1 + round % 2))));
                  if (round % 3 == 0) {
                    cancel.cancel(uuid(10_000 + worker), created.booking().id());
                  }
                  expire.expireDue(5);
                } catch (InsufficientInventoryException
                    | InvalidStateException
                    | ConcurrencyConflictException expected) {
                  // a lost race, a sold-out zone or a booking that expired first: all legitimate
                } catch (Throwable other) {
                  unexpected.add(other);
                }
                app.clock.advance(Duration.ofSeconds(30));
              }
              return null;
            });

    assertThat(unexpected).isEmpty();
    app.assertInventoryInvariant();

    app.clock.advance(Duration.ofDays(1));
    while (expire.expireDue(50) > 0) {
      // drain: every hold has ended now
    }
    assertThat(app.count("SELECT count(*) FROM booking WHERE status = 'PENDING'")).isZero();
    assertThat(app.reserved(VIP_ZONE)).isZero();
    app.assertInventoryInvariant();
  }

  @Test
  void should_not_deadlock_when_bookings_take_the_same_zones_in_opposite_orders() throws Exception {
    app.openEvent(1000, 1000);
    AtomicInteger finished = new AtomicInteger();

    List<Outcome<Void>> outcomes =
        Concurrently.run(
            40,
            t ->
                () -> {
                  List<RequestedItem> items =
                      t % 2 == 0 ? List.of(vip(1), general(1)) : List.of(general(1), vip(1));
                  for (int i = 0; i < 30; i++) {
                    app.tx.run(() -> app.inventory.reserve(EVENT, items));
                    app.tx.run(() -> app.inventory.release(EVENT, items));
                  }
                  finished.incrementAndGet();
                  return null;
                });

    assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
    assertThat(finished.get()).isEqualTo(40);
    assertThat(app.reserved(VIP_ZONE)).isZero();
    assertThat(app.reserved(GENERAL_ZONE)).isZero();
  }

  @Test
  void should_expire_each_booking_exactly_once_when_several_expirers_run_together()
      throws Exception {
    app.openEvent(20, 100);
    CreateBookingService create = app.create();
    for (int i = 0; i < 20; i++) {
      create.create(
          new CreateBookingCommand(uuid(10_000 + i), uuid(20_000 + i), EVENT, List.of(vip(1))));
    }
    app.clock.advance(HOLD.plusSeconds(1));
    ExpireBookingsService expire = app.expire();

    List<Outcome<Integer>> outcomes =
        Concurrently.run(
            4,
            t ->
                () -> {
                  int expired = 0;
                  for (int n = expire.expireDue(3); n > 0; n = expire.expireDue(3)) {
                    expired += n;
                  }
                  return expired;
                });
    int rest = 0;
    for (int n = expire.expireDue(3); n > 0; n = expire.expireDue(3)) {
      rest += n;
    }

    assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
    int total = outcomes.stream().mapToInt(Outcome::value).sum() + rest;
    assertThat(total).isEqualTo(20);
    assertThat(app.count("SELECT count(*) FROM outbox_event WHERE event_type = 'BookingExpired'"))
        .isEqualTo(20);
    assertThat(app.count("SELECT count(*) FROM booking WHERE status = 'EXPIRED'")).isEqualTo(20);
    assertThat(app.reserved(VIP_ZONE)).isZero();
    app.assertInventoryInvariant();
  }
}
