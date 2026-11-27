package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Base for test decorators that change the behaviour of one method and forward the rest. */
public abstract class DelegatingBookingRepository implements BookingRepository {

  protected final BookingRepository delegate;

  protected DelegatingBookingRepository(BookingRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public Booking saveNew(Booking booking, IdempotencyRecord idempotency) {
    return delegate.saveNew(booking, idempotency);
  }

  @Override
  public Booking save(Booking booking) {
    return delegate.save(booking);
  }

  @Override
  public Optional<Booking> findById(UUID id) {
    return delegate.findById(id);
  }

  @Override
  public Optional<StoredBooking> findByIdempotencyKey(UUID userId, UUID key) {
    return delegate.findByIdempotencyKey(userId, key);
  }

  @Override
  public Page<Booking> findByUser(UUID userId, Optional<BookingStatus> status, PageQuery page) {
    return delegate.findByUser(userId, status, page);
  }

  @Override
  public List<Booking> findExpirable(Instant now, int limit) {
    return delegate.findExpirable(now, limit);
  }

  @Override
  public List<UUID> findActiveIdsByEvent(UUID eventId, int limit) {
    return delegate.findActiveIdsByEvent(eventId, limit);
  }
}
