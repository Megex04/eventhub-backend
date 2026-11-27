package com.eventhub.booking.infrastructure.adapter.out.persistence;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.PersistenceIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR 0004 on Booking and Payment: the version rises by exactly one per change, and conflicts fail.
 */
@PersistenceIT
class VersioningIT {

  private static final UUID BOOKING = uuid(1);
  private static final UUID PAYMENT = uuid(700);

  @Autowired private BookingRepository bookings;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @PersistenceContext private EntityManager entityManager;

  private Booking stored() {
    Booking booking = newBooking(BOOKING, USER, at(NOW), new RequestedItem(VIP_ZONE, 2));
    return bookings.saveNew(booking, new IdempotencyRecord(USER, uuid(900), "fp"));
  }

  private long bookingVersion() {
    return jdbc.queryForObject("SELECT version FROM booking WHERE id = ?", Long.class, BOOKING);
  }

  private long paymentVersion() {
    return jdbc.queryForObject("SELECT version FROM payment WHERE id = ?", Long.class, PAYMENT);
  }

  @Test
  void should_raise_the_booking_version_by_exactly_one_per_save_whatever_changed() {
    Booking booking = stored();
    assertThat(bookingVersion()).isZero();

    booking = bookings.save(booking);
    assertThat(booking.version()).isEqualTo(1);

    booking.beginPayment(PAYMENT, "tok_approved", at(NOW.plusSeconds(1)));
    booking = bookings.save(booking);
    assertThat(booking.version()).isEqualTo(2);

    booking.capturePayment(at(NOW.plusSeconds(2)));
    booking = bookings.save(booking);
    assertThat(booking.version()).isEqualTo(3);

    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(3)));
    booking = bookings.save(booking);
    assertThat(booking.version()).isEqualTo(4);
    assertThat(bookingVersion()).isEqualTo(4);
  }

  @Test
  void should_raise_the_payment_version_by_exactly_one_per_change() {
    Booking booking = stored();
    booking.beginPayment(PAYMENT, "tok_approved", at(NOW.plusSeconds(1)));
    booking = bookings.save(booking);
    assertThat(paymentVersion()).isZero();

    booking.capturePayment(at(NOW.plusSeconds(2)));
    booking = bookings.save(booking);
    assertThat(paymentVersion()).isEqualTo(1);

    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(3)));
    booking = bookings.save(booking);
    assertThat(paymentVersion()).isEqualTo(2);

    booking.completeRefund(at(NOW.plusSeconds(4)));
    bookings.save(booking);
    assertThat(paymentVersion()).isEqualTo(3);
  }

  @Test
  void should_leave_the_payment_version_alone_when_only_the_booking_is_saved() {
    Booking booking = stored();
    booking.beginPayment(PAYMENT, "tok_approved", at(NOW.plusSeconds(1)));
    booking = bookings.save(booking);

    bookings.save(booking);
    bookings.save(bookings.findById(BOOKING).orElseThrow());

    assertThat(paymentVersion()).isZero();
    assertThat(bookingVersion()).isEqualTo(3);
  }

  @Test
  void should_write_nothing_of_the_loser_when_two_writers_save_from_the_same_version() {
    stored();
    Booking winner = bookings.findById(BOOKING).orElseThrow();
    Booking loser = bookings.findById(BOOKING).orElseThrow();
    winner.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(1)));
    bookings.save(winner);
    loser.beginPayment(PAYMENT, "tok_approved", at(NOW.plusSeconds(1)));

    assertThatThrownBy(() -> bookings.save(loser)).isInstanceOf(ConcurrencyConflictException.class);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isZero();
    assertThat(bookingVersion()).isEqualTo(1);
  }

  @Test
  void should_fail_the_second_writer_of_a_payment_row_with_an_optimistic_lock_error()
      throws Exception {
    Booking booking = stored();
    booking.beginPayment(PAYMENT, "tok_approved", at(NOW.plusSeconds(1)));
    bookings.save(booking);
    TransactionTemplate first = new TransactionTemplate(transactionManager);
    TransactionTemplate second = new TransactionTemplate(transactionManager);
    CountDownLatch firstHasRead = new CountDownLatch(1);
    CountDownLatch secondCommitted = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> slow =
          pool.submit(() ->
              first.executeWithoutResult(status -> {
                PaymentEntity payment = entityManager.find(PaymentEntity.class, PAYMENT);
                firstHasRead.countDown();
                await(secondCommitted);
                payment.applyState(com.eventhub.booking.domain.PaymentStatus.DECLINED, null, null);
                entityManager.flush();
              }));
      Future<?> fast =
          pool.submit(() -> {
            await(firstHasRead);
            second.executeWithoutResult(status -> {
              PaymentEntity payment = entityManager.find(PaymentEntity.class, PAYMENT);
              payment.applyState(com.eventhub.booking.domain.PaymentStatus.CAPTURED, null, null);
              entityManager.flush();
            });
            secondCommitted.countDown();
          });
      fast.get(30, TimeUnit.SECONDS);

      assertThatThrownBy(() -> slow.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(OptimisticLockException.class);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT))
        .isEqualTo("CAPTURED");
    assertThat(paymentVersion()).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch never opened");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
