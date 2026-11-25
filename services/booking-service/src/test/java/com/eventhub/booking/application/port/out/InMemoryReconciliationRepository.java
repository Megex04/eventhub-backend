package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class InMemoryReconciliationRepository implements ReconciliationRepository {

  private final InMemoryStore store;

  public InMemoryReconciliationRepository(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public List<UUID> findRefundsDue(Instant now, int limit) {
    return store.transaction(() ->
        withPayment(PaymentStatus.REFUND_PENDING)
            .filter(b -> due(b.payment().orElseThrow(), now))
            .limit(limit)
            .map(Booking::id)
            .toList());
  }

  @Override
  public List<UUID> findStaleInitiatedDue(Instant olderThan, Instant now, int limit) {
    return store.transaction(() ->
        withPayment(PaymentStatus.INITIATED)
            .filter(b -> !startedAt(b.payment().orElseThrow()).isAfter(olderThan))
            .filter(b -> due(b.payment().orElseThrow(), now))
            .limit(limit)
            .map(Booking::id)
            .toList());
  }

  @Override
  public int failedAttempts(UUID paymentId) {
    return store.transaction(() -> store.failedAttempts.getOrDefault(paymentId, 0));
  }

  @Override
  public void recordFailure(UUID paymentId, String error, Instant nextAttemptAt) {
    store.transaction(() -> {
      store.failedAttempts.merge(paymentId, 1, Integer::sum);
      store.nextAttempt.put(paymentId, nextAttemptAt);
      return null;
    });
  }

  @Override
  public long countRefundPending() {
    return store.transaction(() -> withPayment(PaymentStatus.REFUND_PENDING).count());
  }

  @Override
  public long countStaleInitiated(Instant olderThan) {
    return store.transaction(() ->
        withPayment(PaymentStatus.INITIATED)
            .filter(b -> !startedAt(b.payment().orElseThrow()).isAfter(olderThan))
            .count());
  }

  private Stream<Booking> withPayment(PaymentStatus status) {
    return store.bookings.values().stream()
        .filter(b -> b.payment().map(p -> p.status() == status).orElse(false))
        .sorted(
            Comparator.comparing((Booking b) -> startedAt(b.payment().orElseThrow()))
                .thenComparing(Booking::id));
  }

  private Instant startedAt(Payment payment) {
    return store.paymentStartedAt.get(payment.id());
  }

  private boolean due(Payment payment, Instant now) {
    Instant next = store.nextAttempt.get(payment.id());
    return next == null || !next.isAfter(now);
  }
}
