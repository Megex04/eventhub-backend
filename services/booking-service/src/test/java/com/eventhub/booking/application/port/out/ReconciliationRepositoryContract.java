package com.eventhub.booking.application.port.out;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.AppFixtures;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The adapter under test must stamp "payment started" with the clock returned by {@link #now()}, so
 * that staleness is decided by time the test controls.
 */
public abstract class ReconciliationRepositoryContract {

  protected abstract BookingRepository bookings();

  protected abstract ReconciliationRepository reconciliation();

  /** Sets the instant the adapters stamp from. */
  protected abstract void setNow(Instant instant);

  protected abstract Instant now();

  private Booking initiated(int n, UUID paymentId) {
    setNow(NOW);
    Booking booking =
        bookings()
            .saveNew(
                AppFixtures.newBooking(uuid(n), USER, at(NOW), new RequestedItem(VIP_ZONE, 1)),
                new IdempotencyRecord(USER, uuid(900 + n), "fp" + n));
    booking.beginPayment(paymentId, "tok_approved", at(NOW));
    return bookings().save(booking);
  }

  private void refundPending(int n, UUID paymentId) {
    Booking booking = initiated(n, paymentId);
    booking.capturePayment(at(NOW.plusSeconds(1)));
    booking.cancel(
        com.eventhub.booking.domain.CancelReason.EVENT_CANCELLED,
        EVENT_STARTS,
        at(NOW.plusSeconds(2)));
    bookings().save(booking);
  }

  @Test
  void should_find_a_charge_left_initiated_only_after_it_is_older_than_the_threshold() {
    initiated(1, uuid(701));
    Instant threshold = NOW.plus(Duration.ofMinutes(5));

    assertThat(reconciliation().findStaleInitiatedDue(NOW.minusSeconds(1), threshold, 10))
        .isEmpty();
    assertThat(reconciliation().findStaleInitiatedDue(NOW, threshold, 10)).containsExactly(uuid(1));
    assertThat(reconciliation().countStaleInitiated(NOW)).isEqualTo(1);
    assertThat(reconciliation().countStaleInitiated(NOW.minusSeconds(1))).isZero();
  }

  @Test
  void should_find_a_refund_that_is_pending_and_count_it() {
    refundPending(1, uuid(701));
    initiated(2, uuid(702));

    assertThat(reconciliation().findRefundsDue(NOW.plusSeconds(10), 10)).containsExactly(uuid(1));
    assertThat(reconciliation().countRefundPending()).isEqualTo(1);
  }

  @Test
  void should_hide_a_payment_until_its_next_attempt_when_a_failure_is_recorded() {
    refundPending(1, uuid(701));
    Instant retryAt = NOW.plus(Duration.ofMinutes(1));

    reconciliation().recordFailure(uuid(701), "gateway down", retryAt);

    assertThat(reconciliation().findRefundsDue(retryAt.minusNanos(1), 10)).isEmpty();
    assertThat(reconciliation().findRefundsDue(retryAt, 10)).containsExactly(uuid(1));
  }

  @Test
  void should_count_the_failed_attempts_of_each_payment() {
    refundPending(1, uuid(701));

    assertThat(reconciliation().failedAttempts(uuid(701))).isZero();
    reconciliation().recordFailure(uuid(701), "first", NOW.plusSeconds(1));
    reconciliation().recordFailure(uuid(701), "second", NOW.plusSeconds(2));

    assertThat(reconciliation().failedAttempts(uuid(701))).isEqualTo(2);
    assertThat(reconciliation().failedAttempts(uuid(702))).isZero();
  }

  @Test
  void should_respect_the_limit_and_the_oldest_first_order() {
    refundPending(1, uuid(701));
    refundPending(2, uuid(702));

    assertThat(reconciliation().findRefundsDue(NOW.plusSeconds(10), 1)).hasSize(1);
    assertThat(reconciliation().findRefundsDue(NOW.plusSeconds(10), 10))
        .containsExactly(uuid(1), uuid(2));
  }
}
