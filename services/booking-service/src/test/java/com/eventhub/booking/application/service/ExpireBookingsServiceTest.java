package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingExpired;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpireBookingsServiceTest {

  private final AppEnv env = new AppEnv().withOpenEvent();
  private final ExpireBookingsService service =
      new ExpireBookingsService(env.tx, env.bookings, env.inventory, env.publisher, env.clock);

  private Booking pendingBooking(long id, int quantity) {
    Booking booking = newBooking(uuid(id), USER, env.clock, new RequestedItem(VIP_ZONE, quantity));
    booking.pullDomainEvents();
    env.bookings.saveNew(booking, new IdempotencyRecord(USER, uuid(900 + id), "fp" + id));
    env.inventory.reserve(EVENT, booking.requestedItems());
    return booking;
  }

  @Test
  void should_expire_the_bookings_whose_hold_ended_and_release_their_tickets() {
    pendingBooking(1, 2);
    env.clock.set(EXPIRES_AT);

    int expired = service.expireDue(10);

    assertThat(expired).isEqualTo(1);
    Booking booking = env.booking(uuid(1));
    assertThat(booking.status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(booking.expiredAt()).contains(EXPIRES_AT);
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingExpired.class)).hasSize(1);
  }

  @Test
  void should_leave_a_booking_alone_one_tick_before_its_hold_ends() {
    pendingBooking(1, 2);
    env.clock.set(EXPIRES_AT.minusNanos(1));

    assertThat(service.expireDue(10)).isZero();

    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.PENDING);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_expire_each_booking_in_its_own_transaction() {
    pendingBooking(1, 1);
    pendingBooking(2, 1);
    pendingBooking(3, 1);
    env.clock.set(EXPIRES_AT);
    int[] transactions = {0};
    ExpireBookingsService counting =
        new ExpireBookingsService(
            new com.eventhub.booking.application.port.out.TransactionRunner() {
              @Override
              public <T> T execute(java.util.function.Supplier<T> work) {
                transactions[0]++;
                return env.tx.execute(work);
              }
            },
            env.bookings,
            env.inventory,
            env.publisher,
            env.clock);

    assertThat(counting.expireDue(10)).isEqualTo(3);

    // One per booking, plus the one that finds nothing left and ends the batch.
    assertThat(transactions[0]).isEqualTo(4);
  }

  @Test
  void should_keep_the_bookings_already_expired_when_a_later_one_fails() {
    pendingBooking(1, 1);
    pendingBooking(2, 1);
    env.clock.set(EXPIRES_AT);
    int[] published = {0};
    ExpireBookingsService failingSecond =
        new ExpireBookingsService(
            env.tx,
            env.bookings,
            env.inventory,
            events -> {
              if (++published[0] == 2) {
                throw new IllegalStateException("outbox down");
              }
              env.publisher.publish(events);
            },
            env.clock);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> failingSecond.expireDue(10))
        .isInstanceOf(IllegalStateException.class);

    long expired =
        java.util.stream.LongStream.rangeClosed(1, 2)
            .filter(id -> env.booking(uuid(id)).status() == BookingStatus.EXPIRED)
            .count();
    assertThat(expired).isEqualTo(1);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(1);
  }

  @Test
  void should_expire_at_most_one_batch_per_call() {
    pendingBooking(1, 1);
    pendingBooking(2, 1);
    pendingBooking(3, 1);
    env.clock.set(EXPIRES_AT);

    assertThat(service.expireDue(2)).isEqualTo(2);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(1);
    assertThat(service.expireDue(2)).isEqualTo(1);
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(service.expireDue(2)).isZero();
  }

  @Test
  void should_release_the_tickets_only_once_however_many_times_the_job_runs() {
    pendingBooking(1, 3);
    pendingBooking(2, 1);
    env.clock.set(EXPIRES_AT);

    service.expireDue(10);
    service.expireDue(10);
    service.expireDue(10);

    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.eventsOfType(BookingExpired.class)).hasSize(2);
  }

  @Test
  void should_not_touch_confirmed_bookings() {
    pendingBooking(1, 2);
    new PayBookingService(
            env.tx, env.bookings, env.publisher, env.ids, env.gateway, env.clock, env.metrics)
        .pay(new PayBookingCommand(USER, uuid(1), "tok_approved"));
    env.clock.advance(Duration.ofHours(1));

    assertThat(service.expireDue(10)).isZero();

    assertThat(env.booking(uuid(1)).status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void
      should_expire_a_booking_whose_payment_is_still_initiated_and_let_the_refund_path_handle_it() {
    pendingBooking(1, 2);
    Booking booking = env.booking(uuid(1));
    UUID paymentId = uuid(700);
    booking.beginPayment(paymentId, "tok_approved", env.clock);
    env.bookings.save(booking);
    env.clock.set(EXPIRES_AT);

    service.expireDue(10);

    Booking expired = env.booking(uuid(1));
    assertThat(expired.status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(expired.payment().orElseThrow().id()).isEqualTo(paymentId);
    assertThat(env.reserved(VIP_ZONE)).isZero();
  }
}
