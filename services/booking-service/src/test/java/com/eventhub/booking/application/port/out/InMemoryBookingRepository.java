package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public final class InMemoryBookingRepository implements BookingRepository {

  private final InMemoryStore store;

  public InMemoryBookingRepository(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public Booking saveNew(Booking booking, IdempotencyRecord idempotency) {
    return store.transaction(() -> {
      var key = new InMemoryStore.IdempotencyKey(idempotency.userId(), idempotency.key());
      if (store.idempotency.containsKey(key)) {
        throw new DuplicateIdempotencyKeyException(idempotency.userId(), idempotency.key());
      }
      if (store.bookings.containsKey(booking.id())) {
        throw new IllegalStateException("Booking " + booking.id() + " already exists");
      }
      Booking stored = InMemoryStore.copy(booking, booking.version());
      store.bookings.put(stored.id(), stored);
      store.idempotency.put(
          key, new InMemoryStore.IdempotencyEntry(stored.id(), idempotency.fingerprint()));
      store.trackPayment(stored);
      return InMemoryStore.copy(stored, stored.version());
    });
  }

  @Override
  public Booking save(Booking booking) {
    return store.transaction(() -> {
      Booking current = store.bookings.get(booking.id());
      if (current == null) {
        throw new IllegalStateException("Booking " + booking.id() + " does not exist");
      }
      if (current.version() != booking.version()) {
        throw new BookingVersionConflictException(booking.id());
      }
      Booking next = InMemoryStore.copy(booking, current.version() + 1);
      store.bookings.put(next.id(), next);
      store.trackPayment(next);
      return InMemoryStore.copy(next, next.version());
    });
  }

  @Override
  public Optional<Booking> findById(UUID id) {
    return store.transaction(() ->
        Optional.ofNullable(store.bookings.get(id)).map(b -> InMemoryStore.copy(b, b.version())));
  }

  @Override
  public Optional<StoredBooking> findByIdempotencyKey(UUID userId, UUID key) {
    return store.transaction(() ->
        Optional.ofNullable(store.idempotency.get(new InMemoryStore.IdempotencyKey(userId, key)))
            .map(entry -> {
              Booking b = store.bookings.get(entry.bookingId());
              return new StoredBooking(InMemoryStore.copy(b, b.version()), entry.fingerprint());
            }));
  }

  @Override
  public Page<Booking> findByUser(UUID userId, Optional<BookingStatus> status, PageQuery page) {
    return store.transaction(() -> {
      Comparator<Booking> order =
          Comparator.comparing(Booking::createdAt).thenComparing(Booking::id);
      List<Booking> all =
          store.bookings.values().stream()
              .filter(b -> b.userId().equals(userId))
              .filter(b -> status.map(s -> b.status() == s).orElse(true))
              .sorted(page.newestFirst() ? order.reversed() : order)
              .toList();
      List<Booking> content =
          all.stream()
              .skip((long) page.page() * page.size())
              .limit(page.size())
              .map(b -> InMemoryStore.copy(b, b.version()))
              .toList();
      return new Page<>(content, page.page(), page.size(), all.size());
    });
  }

  @Override
  public List<Booking> findExpirable(Instant now, int limit) {
    return store.transaction(() ->
        oldestFirst(
                store.bookings.values().stream()
                    .filter(b -> b.status() == BookingStatus.PENDING)
                    .filter(b -> !b.expiresAt().isAfter(now)))
            .sorted(Comparator.comparing(Booking::expiresAt).thenComparing(Booking::id))
            .limit(limit)
            .map(b -> InMemoryStore.copy(b, b.version()))
            .toList());
  }

  @Override
  public List<UUID> findActiveIdsByEvent(UUID eventId, int limit) {
    return store.transaction(() ->
        oldestFirst(
                store.bookings.values().stream()
                    .filter(b -> b.eventId().equals(eventId))
                    .filter(b ->
                        b.status() == BookingStatus.PENDING
                            || b.status() == BookingStatus.CONFIRMED))
            .limit(limit)
            .map(Booking::id)
            .toList());
  }

  private static Stream<Booking> oldestFirst(Stream<Booking> bookings) {
    return bookings.sorted(Comparator.comparing(Booking::createdAt).thenComparing(Booking::id));
  }
}
