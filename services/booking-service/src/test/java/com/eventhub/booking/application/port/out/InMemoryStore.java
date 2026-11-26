package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.ZoneInventory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The state of every in-memory repository, so that a "transaction" can snapshot and restore all of
 * it at once, as a database does. Aggregates are stored and returned as copies: nobody can change
 * what is stored without saving. One re-entrant lock serializes access; the payment gateway is
 * called outside it, exactly as the real saga calls it outside a transaction.
 */
public final class InMemoryStore {

  record IdempotencyKey(UUID userId, UUID key) {}

  record IdempotencyEntry(UUID bookingId, String fingerprint) {}

  record ZoneKey(UUID eventId, UUID zoneId) {}

  private record Snapshot(
      Map<UUID, Booking> bookings,
      Map<IdempotencyKey, IdempotencyEntry> idempotency,
      Map<UUID, EventProjection> projections,
      Map<ZoneKey, ZoneInventory> inventories,
      Set<UUID> processed,
      List<DomainEvent> published,
      Map<UUID, Instant> paymentStartedAt,
      Map<UUID, Integer> failedAttempts,
      Map<UUID, Instant> nextAttempt) {}

  final ReentrantLock lock = new ReentrantLock();
  final Clock clock;
  Map<UUID, Booking> bookings = new LinkedHashMap<>();
  Map<IdempotencyKey, IdempotencyEntry> idempotency = new HashMap<>();
  Map<UUID, EventProjection> projections = new HashMap<>();
  Map<ZoneKey, ZoneInventory> inventories = new LinkedHashMap<>();
  Set<UUID> processed = new HashSet<>();
  List<DomainEvent> published = new ArrayList<>();
  Map<UUID, Instant> paymentStartedAt = new HashMap<>();
  Map<UUID, Integer> failedAttempts = new HashMap<>();
  Map<UUID, Instant> nextAttempt = new HashMap<>();
  private int depth;

  public InMemoryStore(Clock clock) {
    this.clock = clock;
  }

  /** True while the calling thread is inside a transaction (used to prove calls happen outside). */
  public boolean inTransaction() {
    return lock.isHeldByCurrentThread();
  }

  static Booking copy(Booking b, long version) {
    return Booking.restore(
        b.id(),
        b.userId(),
        b.eventId(),
        b.status(),
        b.items(),
        b.total(),
        b.createdAt(),
        b.expiresAt(),
        b.confirmedAt(),
        b.expiredAt(),
        b.cancelledAt(),
        b.cancelReason(),
        b.payment(),
        version);
  }

  static EventProjection copy(EventProjection p) {
    return EventProjection.restore(
        p.eventId(), p.status(), p.startsAt(), p.endsAt(), p.zones(), p.lastOccurredAt());
  }

  static ZoneInventory copy(ZoneInventory z) {
    return ZoneInventory.restore(z.eventId(), z.zoneId(), z.capacity(), z.reserved(), z.version());
  }

  void trackPayment(Booking booking) {
    booking
        .payment()
        .ifPresent(payment -> paymentStartedAt.putIfAbsent(payment.id(), clock.instant()));
  }

  /** Runs {@code work} under the lock; a failure puts back everything it changed. */
  <T> T transaction(java.util.function.Supplier<T> work) {
    lock.lock();
    Snapshot before = depth == 0 ? snapshot() : null;
    depth++;
    try {
      return work.get();
    } catch (RuntimeException | Error e) {
      if (before != null) {
        restore(before);
      }
      throw e;
    } finally {
      depth--;
      lock.unlock();
    }
  }

  private Snapshot snapshot() {
    return new Snapshot(
        new LinkedHashMap<>(bookings),
        new HashMap<>(idempotency),
        new HashMap<>(projections),
        new LinkedHashMap<>(inventories),
        new HashSet<>(processed),
        new ArrayList<>(published),
        new HashMap<>(paymentStartedAt),
        new HashMap<>(failedAttempts),
        new HashMap<>(nextAttempt));
  }

  private void restore(Snapshot s) {
    bookings = s.bookings();
    idempotency = s.idempotency();
    projections = s.projections();
    inventories = s.inventories();
    processed = s.processed();
    published = s.published();
    paymentStartedAt = s.paymentStartedAt();
    failedAttempts = s.failedAttempts();
    nextAttempt = s.nextAttempt();
  }
}
