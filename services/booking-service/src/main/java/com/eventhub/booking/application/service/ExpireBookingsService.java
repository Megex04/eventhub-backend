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
 * Expires PENDING bookings whose hold ended, one transaction per booking: a failure on one never
 * undoes the others, and a lock is held only for the booking being expired. Each transaction reads
 * its booking with {@code findExpirable(now, 1)}, which the real adapter locks (SKIP LOCKED), so
 * several instances never take the same booking and a payment saving at the same moment waits
 * instead of racing. Tickets are released here, on the transition, and nowhere else: a booking that
 * is no longer PENDING is never returned, so they cannot be released twice.
 *
 * <p>A booking with a payment in flight expires all the same; the payment saga notices when it
 * tries to confirm and refunds the charge (ADR 0006).
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
    int expired = 0;
    while (expired < batchSize && transactions.execute(this::expireNext)) {
      expired++;
    }
    return expired;
  }

  private boolean expireNext() {
    List<Booking> due = bookings.findExpirable(clock.instant(), 1);
    due.forEach(this::expire);
    return !due.isEmpty();
  }

  private void expire(Booking booking) {
    booking.expire(clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    bookings.save(booking);
    inventory.release(booking.eventId(), booking.requestedItems());
    publisher.publish(events);
  }
}
