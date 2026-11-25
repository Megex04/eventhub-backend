package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.IdempotencyKeyReuseException;
import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.CreateBookingResult;
import com.eventhub.booking.application.port.in.CreateBookingUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.DuplicateIdempotencyKeyException;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.IdGenerator;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingPolicy;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.EventProjection;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Creates a PENDING booking and holds its tickets in one transaction. The booking is inserted
 * before the tickets are reserved: if a concurrent request already used the Idempotency-Key the
 * insert fails first and nothing was held; if the zones are short the whole transaction rolls back.
 */
public class CreateBookingService implements CreateBookingUseCase {

  private final TransactionRunner transactions;
  private final BookingRepository bookings;
  private final InventoryRepository inventory;
  private final EventProjectionRepository projections;
  private final DomainEventPublisher publisher;
  private final IdGenerator ids;
  private final Clock clock;
  private final BookingPolicy policy;

  public CreateBookingService(
      TransactionRunner transactions,
      BookingRepository bookings,
      InventoryRepository inventory,
      EventProjectionRepository projections,
      DomainEventPublisher publisher,
      IdGenerator ids,
      Clock clock,
      BookingPolicy policy) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.inventory = inventory;
    this.projections = projections;
    this.publisher = publisher;
    this.ids = ids;
    this.clock = clock;
    this.policy = policy;
  }

  @Override
  public CreateBookingResult create(CreateBookingCommand command) {
    String fingerprint = RequestFingerprint.of(command.eventId(), command.items());
    try {
      return transactions.execute(() -> createOrReplay(command, fingerprint));
    } catch (DuplicateIdempotencyKeyException lostTheRace) {
      // The winner committed meanwhile: answer with its booking, as a plain retry would.
      return transactions.execute(
          () -> replay(command, fingerprint).orElseThrow(() -> lostTheRace));
    }
  }

  private CreateBookingResult createOrReplay(CreateBookingCommand command, String fingerprint) {
    Optional<CreateBookingResult> earlier = replay(command, fingerprint);
    if (earlier.isPresent()) {
      return earlier.get();
    }
    EventProjection event =
        projections
            .findById(command.eventId())
            .filter(projection -> !projection.isTombstone())
            .orElseThrow(() -> new NotFoundException("Event", command.eventId()));
    Booking booking =
        Booking.create(ids.newId(), command.userId(), event, command.items(), policy, clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    Booking saved =
        bookings.saveNew(
            booking,
            new IdempotencyRecord(command.userId(), command.idempotencyKey(), fingerprint));
    inventory.reserve(command.eventId(), booking.requestedItems());
    publisher.publish(events);
    return new CreateBookingResult(saved, true);
  }

  private Optional<CreateBookingResult> replay(CreateBookingCommand command, String fingerprint) {
    return bookings
        .findByIdempotencyKey(command.userId(), command.idempotencyKey())
        .map(stored -> {
          if (!stored.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException(command.idempotencyKey());
          }
          return new CreateBookingResult(stored.booking(), false);
        });
  }
}
