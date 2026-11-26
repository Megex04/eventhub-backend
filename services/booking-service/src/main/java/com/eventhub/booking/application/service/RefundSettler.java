package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.PaymentGatewayException;
import com.eventhub.booking.application.port.out.RefundRequest;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentStatus;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gives the money back for a payment that is REFUND_PENDING: the gateway call happens outside any
 * transaction, and only an answer from the gateway lets a short transaction mark it REFUNDED. If
 * the gateway fails the payment simply stays REFUND_PENDING for the reconciler.
 */
final class RefundSettler {

  private final TransactionRunner transactions;
  private final BookingRepository bookings;
  private final DomainEventPublisher publisher;
  private final PaymentGateway gateway;
  private final Clock clock;

  RefundSettler(
      TransactionRunner transactions,
      BookingRepository bookings,
      DomainEventPublisher publisher,
      PaymentGateway gateway,
      Clock clock) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.publisher = publisher;
    this.gateway = gateway;
    this.clock = clock;
  }

  /**
   * True when the payment is REFUNDED after the call (also when somebody else got there first);
   * false when the gateway could not refund it now.
   */
  boolean settle(UUID bookingId) {
    Optional<Payment> pending = pendingRefund(bookingId);
    if (pending.isEmpty()) {
      return true;
    }
    Payment payment = pending.get();
    try {
      gateway.refund(new RefundRequest(payment.id(), payment.amount()));
    } catch (PaymentGatewayException unreachable) {
      return false;
    }
    VersionRetry.run(transactions, () -> complete(bookingId));
    return true;
  }

  private Optional<Payment> pendingRefund(UUID bookingId) {
    return bookings
        .findById(bookingId)
        .flatMap(Booking::payment)
        .filter(payment -> payment.status() == PaymentStatus.REFUND_PENDING);
  }

  private Booking complete(UUID bookingId) {
    Booking booking = bookings.findById(bookingId).orElseThrow();
    boolean stillPending =
        booking.payment().filter(p -> p.status() == PaymentStatus.REFUND_PENDING).isPresent();
    if (!stillPending) {
      return booking;
    }
    booking.completeRefund(clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    Booking saved = bookings.save(booking);
    publisher.publish(events);
    return saved;
  }
}
