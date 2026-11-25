package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of the Booking aggregate. Every save of an existing booking raises its version by
 * exactly one and fails with {@link BookingVersionConflictException} when the aggregate is stale;
 * the saved aggregate is returned and is the one to use from then on.
 */
public interface BookingRepository {

  /** Stores a new booking with its idempotency record; fails if the user already used the key. */
  Booking saveNew(Booking booking, IdempotencyRecord idempotency);

  Booking save(Booking booking);

  Optional<Booking> findById(UUID id);

  Optional<StoredBooking> findByIdempotencyKey(UUID userId, UUID key);

  Page<Booking> findByUser(UUID userId, Optional<BookingStatus> status, PageQuery page);

  /**
   * PENDING bookings whose hold ended ({@code expiresAt <= now}), oldest first. The real adapter
   * locks them with {@code FOR UPDATE SKIP LOCKED} so several instances never take the same one.
   */
  List<Booking> findExpirable(Instant now, int limit);

  /** Ids of the PENDING or CONFIRMED bookings of an event, oldest first. */
  List<UUID> findActiveIdsByEvent(UUID eventId, int limit);
}
