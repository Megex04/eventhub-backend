package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.in.ExpireBookingsUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.DomainEvent;
import java.time.Clock;
import java.util.List;

/**
 * Expires one batch of PENDING bookings whose hold ended, in one transaction. The batch is read
 * with {@code findExpirable}, which the real adapter locks (SKIP LOCKED), so several instances
 * never take the same booking and a payment saving at the same moment waits instead of racing.
 * Tickets are released here, on the transition, and nowhere else: a booking that is no longer
 * PENDING is never returned, so they cannot be released twice.
 */
public class ExpireBookingsService implements ExpireBookingsUseCase {

  private final TransactionRunner transactions;
  private final BookingRepository bookings;
  private final InventoryRepository inventory;
  private final DomainEventPublisher publisher;
  private final Clock clock;

  public ExpireBookingsService(
      TransactionRunner transactions,
      BookingRepository bookings,
      InventoryRepository inventory,
      DomainEventPublisher publisher,
      Clock clock) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.inventory = inventory;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  public int expireDue(int batchSize) {
    return transactions.execute(() -> expireBatch(batchSize));
  }

  private int expireBatch(int batchSize) {
    List<Booking> due = bookings.findExpirable(clock.instant(), batchSize);
    due.forEach(this::expire);
    return due.size();
  }

  private void expire(Booking booking) {
    booking.expire(clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    bookings.save(booking);
    inventory.release(booking.eventId(), booking.requestedItems());
    publisher.publish(events);
  }
}
