package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.Booking.CaptureOutcome;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.PaymentStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Applies the answer of the gateway to the booking in a short transaction. Shared by the request
 * that charged and by the reconciler that repeats a charge whose outcome was unknown, so both
 * decide the same way. Each call reloads the booking, and does nothing if somebody else already
 * settled that very payment.
 */
final class PaymentOutcomes {

  /** The booking as saved and what the capture meant for it. */
  record Captured(Booking booking, CaptureOutcome outcome) {}

  private final TransactionRunner transactions;
  private final BookingRepository bookings;
  private final DomainEventPublisher publisher;
  private final Clock clock;

  PaymentOutcomes(
      TransactionRunner transactions,
      BookingRepository bookings,
      DomainEventPublisher publisher,
      Clock clock) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.publisher = publisher;
    this.clock = clock;
  }

  /** The gateway approved: confirm if still payable, otherwise the payment awaits its refund. */
  Captured capture(UUID bookingId, UUID paymentId) {
    return VersionRetry.run(transactions, () -> captureOnce(bookingId, paymentId));
  }

  /** The gateway declined: the booking stays PENDING and can be paid again. */
  void decline(UUID bookingId, UUID paymentId) {
    VersionRetry.run(transactions, () -> declineOnce(bookingId, paymentId));
  }

  private Captured captureOnce(UUID bookingId, UUID paymentId) {
    Booking booking = bookings.findById(bookingId).orElseThrow();
    if (settledMeanwhile(booking, paymentId)) {
      return new Captured(booking, outcomeOf(booking));
    }
    CaptureOutcome outcome = booking.capturePayment(clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    Booking saved = bookings.save(booking);
    publisher.publish(events);
    return new Captured(saved, outcome);
  }

  private Booking declineOnce(UUID bookingId, UUID paymentId) {
    Booking booking = bookings.findById(bookingId).orElseThrow();
    if (settledMeanwhile(booking, paymentId)) {
      return booking;
    }
    booking.declinePayment();
    return bookings.save(booking);
  }

  private static boolean settledMeanwhile(Booking booking, UUID paymentId) {
    return booking
        .payment()
        .filter(payment -> payment.id().equals(paymentId))
        .filter(payment -> payment.status() != PaymentStatus.INITIATED)
        .isPresent();
  }

  private static CaptureOutcome outcomeOf(Booking booking) {
    return booking.status() == BookingStatus.CONFIRMED
        ? CaptureOutcome.CONFIRMED
        : CaptureOutcome.REFUND_REQUIRED;
  }
}
