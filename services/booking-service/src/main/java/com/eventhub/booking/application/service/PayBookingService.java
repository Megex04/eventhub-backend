package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.PaymentDeclinedException;
import com.eventhub.booking.application.exception.PaymentRefundPendingException;
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
import com.eventhub.booking.application.port.out.PaymentMetrics;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.Booking.CaptureOutcome;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentInProgressException;
import java.time.Clock;
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
  private final IdGenerator ids;
  private final PaymentGateway gateway;
  private final Clock clock;
  private final PaymentOutcomes outcomes;
  private final RefundSettler refunds;
  private final PaymentMetrics metrics;

  public PayBookingService(
      TransactionRunner transactions,
      BookingRepository bookings,
      DomainEventPublisher publisher,
      IdGenerator ids,
      PaymentGateway gateway,
      Clock clock,
      PaymentMetrics metrics) {
    this.transactions = transactions;
    this.bookings = bookings;
    this.ids = ids;
    this.gateway = gateway;
    this.clock = clock;
    this.outcomes = new PaymentOutcomes(transactions, bookings, publisher, clock);
    this.refunds = new RefundSettler(transactions, bookings, publisher, gateway, clock);
    this.metrics = metrics;
  }

  @Override
  public Booking pay(PayBookingCommand command) {
    Started started = VersionRetry.run(transactions, () -> begin(command));
    if (started.alreadyConfirmed()) {
      return started.booking();
    }
    Payment payment = started.payment();
    UUID bookingId = started.booking().id();
    return switch (charge(payment)) {
      case ChargeResult.Approved approved -> applyApproval(bookingId, payment.id());
      case ChargeResult.Declined declined -> applyDecline(bookingId, payment.id());
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
          new ChargeRequest(payment.id(), payment.amount(), payment.paymentToken().orElseThrow()));
    } catch (PaymentGatewayException unknownOutcome) {
      throw new PaymentInProgressException(payment.id());
    }
  }

  /** Step 3, approved: confirm if still payable, otherwise give the money back. */
  private Booking applyApproval(UUID bookingId, UUID paymentId) {
    PaymentOutcomes.Captured captured = outcomes.capture(bookingId, paymentId);
    if (captured.outcome() == CaptureOutcome.CONFIRMED) {
      return captured.booking();
    }
    // The captured state is committed: from here on the refund is best effort.
    if (refunds.settle(bookingId)) {
      throw new PaymentRefundedException();
    }
    metrics.immediateRefundFailed(bookingId);
    throw new PaymentRefundPendingException();
  }

  /** Step 3, declined: the booking stays PENDING and can be paid again. */
  private Booking applyDecline(UUID bookingId, UUID paymentId) {
    outcomes.decline(bookingId, paymentId);
    throw new PaymentDeclinedException();
  }

  private record Started(Booking booking, Payment payment) {

    boolean alreadyConfirmed() {
      return payment == null;
    }
  }
}
