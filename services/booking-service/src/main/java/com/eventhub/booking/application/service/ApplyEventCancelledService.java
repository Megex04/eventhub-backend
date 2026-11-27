package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.in.ApplyEventCancelledUseCase;
import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.EventProjection.ApplyResult;
import com.eventhub.booking.domain.EventSaleStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies {@code EventCancelled}: first the projection (a tombstone if the event was never seen),
 * then its bookings in batches, one transaction per booking. The cascade is resumable: each booking
 * is decided by its own state (a finished one is skipped), and the message is marked processed only
 * at the very end, so a crash midway just means the redelivered message continues the work.
 *
 * <p>The refund of a confirmed booking is left REFUND_PENDING for the reconciler; the cascade never
 * calls the payment gateway.
 */
public class ApplyEventCancelledService implements ApplyEventCancelledUseCase {

  private final TransactionRunner transactions;
  private final EventProjectionRepository projections;
  private final BookingRepository bookings;
  private final InventoryRepository inventory;
  private final ProcessedMessageRepository processed;
  private final DomainEventPublisher publisher;
  private final Clock clock;
  private final int batchSize;

  public ApplyEventCancelledService(
      TransactionRunner transactions,
      EventProjectionRepository projections,
      BookingRepository bookings,
      InventoryRepository inventory,
      ProcessedMessageRepository processed,
      DomainEventPublisher publisher,
      Clock clock,
      int batchSize) {
    this.transactions = transactions;
    this.projections = projections;
    this.bookings = bookings;
    this.inventory = inventory;
    this.processed = processed;
    this.publisher = publisher;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Override
  public void apply(EventCancelledMessage message) {
    if (processed.isProcessed(message.messageId())) {
      return;
    }
    EventProjection projection = transactions.execute(() -> cancelProjection(message));
    if (projection.status() == EventSaleStatus.CANCELLED) {
      cancelBookings(message.eventId(), startOf(projection, message));
    }
    transactions.run(() -> processed.markProcessed(message.messageId()));
  }

  private EventProjection cancelProjection(EventCancelledMessage message) {
    EventProjection projection = projections.findById(message.eventId()).orElse(null);
    if (projection == null) {
      EventProjection tombstone =
          EventProjection.cancelledTombstone(message.eventId(), message.occurredAt());
      projections.save(tombstone);
      return tombstone;
    }
    if (projection.applyCancelled(message.occurredAt()) == ApplyResult.APPLIED) {
      projections.save(projection);
    }
    return projection;
  }

  /** Only read by cancel(): a cancellation by the event ignores the start of the event. */
  private static Instant startOf(EventProjection projection, EventCancelledMessage message) {
    return projection.startsAt().orElse(message.occurredAt());
  }

  private void cancelBookings(UUID eventId, Instant eventStartsAt) {
    List<UUID> batch = bookings.findActiveIdsByEvent(eventId, batchSize);
    while (!batch.isEmpty()) {
      for (UUID bookingId : batch) {
        VersionRetry.run(transactions, () -> cancelOne(bookingId, eventStartsAt));
      }
      batch = bookings.findActiveIdsByEvent(eventId, batchSize);
    }
  }

  private Booking cancelOne(UUID bookingId, Instant eventStartsAt) {
    Booking booking = bookings.findById(bookingId).orElseThrow();
    if (booking.status() != BookingStatus.PENDING && booking.status() != BookingStatus.CONFIRMED) {
      return booking;
    }
    booking.cancel(CancelReason.EVENT_CANCELLED, eventStartsAt, clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    Booking saved = bookings.save(booking);
    inventory.release(booking.eventId(), booking.requestedItems());
    publisher.publish(events);
    return saved;
  }
}
