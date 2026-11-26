package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.CancelBookingUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cancels the caller's booking and releases its tickets in one transaction, then (outside it) tries
 * the refund right away if the booking was paid. A refund the gateway cannot do now stays
 * REFUND_PENDING for the reconciler.
 */
public class CancelBookingService implements CancelBookingUseCase {

  private final TransactionRunner transactions;
  private final BookingRepository bookings;
  private final InventoryRepository inventory;
  private final EventProjectionRepository projections;
  private final DomainEventPublisher publisher;
  private final Clock clock;
  private final RefundSettler refunds;

  public CancelBookingService(
      TransactionRunner transactions,
      BookingRepository bookings,
      InventoryRepository inventory,
      EventProjectionRepository projections,
      DomainEventPublisher publisher,
      PaymentGateway gateway,
      Clock clock) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.inventory = inventory;
    this.projections = projections;
    this.publisher = publisher;
    this.clock = clock;
    this.refunds = new RefundSettler(transactions, bookings, publisher, gateway, clock);
  }

  @Override
  public Booking cancel(UUID userId, UUID bookingId) {
    Booking cancelled = VersionRetry.run(transactions, () -> cancelAndRelease(userId, bookingId));
    if (refundIsPending(cancelled)) {
      refunds.settle(bookingId);
    }
    return bookings.findById(bookingId).orElseThrow();
  }

  private Booking cancelAndRelease(UUID userId, UUID bookingId) {
    Booking booking = OwnedBooking.find(bookings, userId, bookingId);
    Instant eventStartsAt =
        projections
            .findById(booking.eventId())
            .flatMap(projection -> projection.startsAt())
            .orElseThrow(() -> new NotFoundException("Event", booking.eventId()));
    booking.cancel(CancelReason.USER_REQUEST, eventStartsAt, clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    Booking saved = bookings.save(booking);
    inventory.release(booking.eventId(), booking.requestedItems());
    publisher.publish(events);
    return saved;
  }

  private static boolean refundIsPending(Booking booking) {
    return booking
        .payment()
        .filter(payment -> payment.status() == PaymentStatus.REFUND_PENDING)
        .isPresent();
  }
}
