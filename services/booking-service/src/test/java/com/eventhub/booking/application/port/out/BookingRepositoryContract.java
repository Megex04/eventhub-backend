package com.eventhub.booking.application.port.out;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.PaymentStatus;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What every BookingRepository must do, whatever it is built on. Run against the in-memory fake now
 * and against JPA/PostgreSQL later: the same behaviour, one suite.
 */
public abstract class BookingRepositoryContract {

  protected abstract BookingRepository repository();

  private static UUID key(int n) {
    return uuid(500 + n);
  }

  private Booking store(UUID id, UUID userId, Instant createdAt, int keyNumber) {
    Booking booking = newBooking(id, userId, at(createdAt), new RequestedItem(VIP_ZONE, 1));
    return repository()
        .saveNew(booking, new IdempotencyRecord(userId, key(keyNumber), "fp-" + keyNumber));
  }

  @Test
  void should_return_version_zero_and_find_the_booking_when_it_is_stored_new() {
    Booking saved = store(uuid(1), USER, NOW, 1);

    assertThat(saved.version()).isZero();
    Booking found = repository().findById(uuid(1)).orElseThrow();
    assertThat(found.status()).isEqualTo(BookingStatus.PENDING);
    assertThat(found.items()).isEqualTo(saved.items());
    assertThat(found.total()).isEqualTo(saved.total());
    assertThat(found.expiresAt()).isEqualTo(saved.expiresAt());
    assertThat(found.version()).isZero();
  }

  @Test
  void should_find_nothing_when_the_booking_does_not_exist() {
    assertThat(repository().findById(uuid(99))).isEmpty();
  }

  @Test
  void should_raise_the_version_by_exactly_one_when_an_existing_booking_is_saved() {
    Booking saved = store(uuid(1), USER, NOW, 1);
    saved.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(5)));

    Booking after = repository().save(saved);

    assertThat(after.version()).isEqualTo(1);
    assertThat(after.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(repository().findById(uuid(1)).orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void should_raise_the_version_by_one_even_when_nothing_changed() {
    Booking saved = store(uuid(1), USER, NOW, 1);

    assertThat(repository().save(saved).version()).isEqualTo(1);
  }

  @Test
  void should_reject_a_stale_save_and_keep_the_stored_state_when_the_version_is_old() {
    Booking first = store(uuid(1), USER, NOW, 1);
    Booking stale = repository().findById(uuid(1)).orElseThrow();
    first.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(5)));
    repository().save(first);
    stale.expire(at(NOW.plus(HOLD)));

    assertThatThrownBy(() -> repository().save(stale))
        .isInstanceOf(BookingVersionConflictException.class);
    assertThat(repository().findById(uuid(1)).orElseThrow().status())
        .isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void should_let_exactly_one_writer_win_when_two_save_the_same_version_at_once() throws Exception {
    store(uuid(1), USER, NOW, 1);
    Booking left = repository().findById(uuid(1)).orElseThrow();
    Booking right = repository().findById(uuid(1)).orElseThrow();
    left.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(1)));
    right.expire(at(NOW.plus(HOLD)));
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger won = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> writers =
          List.of(left, right).stream()
              .map(booking ->
                  pool.submit(() -> {
                    start.await();
                    try {
                      repository().save(booking);
                      won.incrementAndGet();
                    } catch (BookingVersionConflictException e) {
                      conflicts.incrementAndGet();
                    }
                    return null;
                  }))
              .collect(java.util.stream.Collectors.toList());
      start.countDown();
      for (Future<?> writer : writers) {
        writer.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(won.get()).isEqualTo(1);
    assertThat(conflicts.get()).isEqualTo(1);
    assertThat(repository().findById(uuid(1)).orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void should_keep_the_payment_through_a_round_trip_when_the_booking_is_saved() {
    Booking saved = store(uuid(1), USER, NOW, 1);
    saved.beginPayment(uuid(700), "tok_approved", at(NOW.plusSeconds(1)));
    saved.capturePayment(at(NOW.plusSeconds(2)));
    saved.pullDomainEvents();
    repository().save(saved);

    Booking found = repository().findById(uuid(1)).orElseThrow();

    assertThat(found.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(found.confirmedAt()).contains(NOW.plusSeconds(2));
    assertThat(found.payment())
        .hasValueSatisfying(payment -> {
          assertThat(payment.id()).isEqualTo(uuid(700));
          assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
          assertThat(payment.paymentToken()).isEqualTo("tok_approved");
          assertThat(payment.amount()).isEqualTo(found.total());
        });
  }

  @Test
  void should_reject_a_second_booking_when_the_user_reuses_an_idempotency_key() {
    store(uuid(1), USER, NOW, 1);
    Booking another = newBooking(uuid(2), USER, at(NOW), new RequestedItem(VIP_ZONE, 1));

    assertThatThrownBy(() ->
            repository().saveNew(another, new IdempotencyRecord(USER, key(1), "other-fingerprint")))
        .isInstanceOf(DuplicateIdempotencyKeyException.class);
    assertThat(repository().findById(uuid(2))).isEmpty();
  }

  @Test
  void should_accept_the_same_idempotency_key_when_another_user_uses_it() {
    store(uuid(1), USER, NOW, 1);
    Booking theirs = newBooking(uuid(2), OTHER_USER, at(NOW), new RequestedItem(VIP_ZONE, 1));

    Booking saved = repository().saveNew(theirs, new IdempotencyRecord(OTHER_USER, key(1), "fp-1"));

    assertThat(saved.id()).isEqualTo(uuid(2));
  }

  @Test
  void should_find_the_booking_and_its_fingerprint_when_the_key_was_used() {
    store(uuid(1), USER, NOW, 1);

    StoredBooking found = repository().findByIdempotencyKey(USER, key(1)).orElseThrow();

    assertThat(found.booking().id()).isEqualTo(uuid(1));
    assertThat(found.fingerprint()).isEqualTo("fp-1");
    assertThat(repository().findByIdempotencyKey(OTHER_USER, key(1))).isEmpty();
    assertThat(repository().findByIdempotencyKey(USER, key(2))).isEmpty();
  }

  @Test
  void should_page_only_the_own_bookings_newest_first_when_listing_by_user() {
    store(uuid(1), USER, NOW, 1);
    store(uuid(2), USER, NOW.plusSeconds(10), 2);
    store(uuid(3), USER, NOW.plusSeconds(20), 3);
    store(uuid(4), OTHER_USER, NOW.plusSeconds(30), 4);

    Page<Booking> first =
        repository().findByUser(USER, Optional.empty(), new PageQuery(0, 2, true));
    Page<Booking> second =
        repository().findByUser(USER, Optional.empty(), new PageQuery(1, 2, true));

    assertThat(first.content()).extracting(Booking::id).containsExactly(uuid(3), uuid(2));
    assertThat(second.content()).extracting(Booking::id).containsExactly(uuid(1));
    assertThat(first.totalElements()).isEqualTo(3);
    assertThat(first.totalPages()).isEqualTo(2);
  }

  @Test
  void should_order_oldest_first_when_requested() {
    store(uuid(1), USER, NOW, 1);
    store(uuid(2), USER, NOW.plusSeconds(10), 2);

    Page<Booking> page =
        repository().findByUser(USER, Optional.empty(), new PageQuery(0, 10, false));

    assertThat(page.content()).extracting(Booking::id).containsExactly(uuid(1), uuid(2));
  }

  @Test
  void should_filter_by_status_when_listing_by_user() {
    store(uuid(1), USER, NOW, 1);
    Booking cancelled = store(uuid(2), USER, NOW.plusSeconds(10), 2);
    cancelled.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(11)));
    repository().save(cancelled);

    Page<Booking> page =
        repository()
            .findByUser(USER, Optional.of(BookingStatus.CANCELLED), new PageQuery(0, 10, true));

    assertThat(page.content()).extracting(Booking::id).containsExactly(uuid(2));
    assertThat(page.totalElements()).isEqualTo(1);
  }

  @Test
  void
      should_find_only_pending_bookings_whose_hold_ended_oldest_first_when_looking_for_expirable() {
    store(uuid(1), USER, NOW, 1);
    store(uuid(2), USER, NOW.plusSeconds(60), 2);
    store(uuid(3), USER, NOW.plusSeconds(120), 3);
    Booking cancelled = store(uuid(4), USER, NOW, 4);
    cancelled.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(1)));
    repository().save(cancelled);

    Instant now = NOW.plus(HOLD).plusSeconds(60);

    assertThat(repository().findExpirable(now, 10))
        .extracting(Booking::id)
        .containsExactly(uuid(1), uuid(2));
    assertThat(repository().findExpirable(now, 1)).extracting(Booking::id).containsExactly(uuid(1));
    assertThat(repository().findExpirable(NOW.plus(HOLD).minusNanos(1), 10)).isEmpty();
  }

  @Test
  void should_find_the_active_bookings_of_the_event_when_looking_for_the_cascade() {
    store(uuid(1), USER, NOW, 1);
    Booking confirmed = store(uuid(2), USER, NOW.plusSeconds(1), 2);
    confirmed.beginPayment(uuid(701), "tok_approved", at(NOW.plusSeconds(2)));
    confirmed.capturePayment(at(NOW.plusSeconds(3)));
    repository().save(confirmed);
    Booking expired = store(uuid(3), USER, NOW.plusSeconds(2), 3);
    expired.expire(at(NOW.plus(HOLD).plusSeconds(10)));
    repository().save(expired);

    assertThat(repository().findActiveIdsByEvent(EVENT, 10)).containsExactly(uuid(1), uuid(2));
    assertThat(repository().findActiveIdsByEvent(EVENT, 1)).containsExactly(uuid(1));
    assertThat(repository().findActiveIdsByEvent(uuid(999), 10)).isEmpty();
  }
}
