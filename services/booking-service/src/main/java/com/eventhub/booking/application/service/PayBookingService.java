package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.PaymentDeclinedException;
import com.eventhub.booking.application.exception.PaymentRefundedException;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.in.PayBookingUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ChargeRequest;
import com.eventhub.booking.application.port.out.ChargeResult;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.IdGenerator;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.PaymentGatewayException;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.Booking.CaptureOutcome;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentInProgressException;
import com.eventhub.booking.domain.PaymentStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The payment saga, deliberately not one transaction:
 *
 * <ol>
 *   <li>a short transaction validates the booking and stores the payment as INITIATED, so a charge
 *       can never exist without a trace in our database;
 *   <li>the gateway is charged outside any transaction (no locks are held during an external call),
 *       with the payment id as idempotency key;
 *   <li>a short transaction applies the result, reloading the booking: CONFIRMED if it is still
 *       payable, otherwise the money goes back.
 * </ol>
 *
 * If the charge ends without an answer the payment stays INITIATED and the caller gets
 * payment-in-progress; the reconciler resolves it later.
 */
public class PayBookingService implements PayBookingUseCase {

  private final TransactionRunner transactions;
  private final BookingRepository bookings;
  private final DomainEventPublisher publisher;
  private final IdGenerator ids;
  private final PaymentGateway gateway;
  private final Clock clock;
  private final RefundSettler refunds;

  public PayBookingService(
      TransactionRunner transactions,
      BookingRepository bookings,
      DomainEventPublisher publisher,
      IdGenerator ids,
      PaymentGateway gateway,
      Clock clock) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.publisher = publisher;
    this.ids = ids;
    this.gateway = gateway;
    this.clock = clock;
    this.refunds = new RefundSettler(transactions, bookings, publisher, gateway, clock);
  }

  @Override
  public Booking pay(PayBookingCommand command) {
    Started started = VersionRetry.run(transactions, () -> begin(command));
    if (started.alreadyConfirmed()) {
      return started.booking();
    }
    Payment payment = started.payment();
    ChargeResult result = charge(payment);
    return switch (result) {
      case ChargeResult.Approved approved -> applyApproval(started.booking().id(), payment.id());
      case ChargeResult.Declined declined -> applyDecline(started.booking().id());
    };
  }

  /** Step 1: validate and record the intention to charge. */
  private Started begin(PayBookingCommand command) {
    Booking booking = OwnedBooking.find(bookings, command.userId(), command.bookingId());
    if (booking.status() == BookingStatus.CONFIRMED) {
      return new Started(booking, null);
    }
    Payment payment = booking.beginPayment(ids.newId(), command.paymentToken(), clock);
    return new Started(bookings.save(booking), payment);
  }

  /** Step 2: outside any transaction. No answer means the outcome is unknown. */
  private ChargeResult charge(Payment payment) {
    try {
      return gateway.charge(
          new ChargeRequest(payment.id(), payment.amount(), payment.paymentToken()));
    } catch (PaymentGatewayException unknownOutcome) {
      throw new PaymentInProgressException(payment.id());
    }
  }

  /** Step 3, approved: confirm if still payable, otherwise give the money back. */
  private Booking applyApproval(UUID bookingId, UUID paymentId) {
    Captured captured = VersionRetry.run(transactions, () -> capture(bookingId, paymentId));
    if (captured.outcome() == CaptureOutcome.CONFIRMED) {
      return captured.booking();
    }
    refunds.settle(bookingId);
    throw new PaymentRefundedException();
  }

  private Captured capture(UUID bookingId, UUID paymentId) {
    Booking booking = bookings.findById(bookingId).orElseThrow();
    if (resolvedMeanwhile(booking, paymentId)) {
      return new Captured(booking, outcomeOf(booking));
    }
    CaptureOutcome outcome = booking.capturePayment(clock);
    List<DomainEvent> events = booking.pullDomainEvents();
    Booking saved = bookings.save(booking);
    publisher.publish(events);
    return new Captured(saved, outcome);
  }

  /** The reconciler may have settled this very payment while the answer was on its way. */
  private static boolean resolvedMeanwhile(Booking booking, UUID paymentId) {
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

  /** Step 3, declined: the booking stays PENDING and can be paid again. */
  private Booking applyDecline(UUID bookingId) {
    VersionRetry.run(transactions, () -> decline(bookingId));
    throw new PaymentDeclinedException();
  }

  private Booking decline(UUID bookingId) {
    Booking booking = bookings.findById(bookingId).orElseThrow();
    booking.declinePayment();
    return bookings.save(booking);
  }

  private record Started(Booking booking, Payment payment) {

    boolean alreadyConfirmed() {
      return payment == null;
    }
  }

  private record Captured(Booking booking, CaptureOutcome outcome) {}
}
