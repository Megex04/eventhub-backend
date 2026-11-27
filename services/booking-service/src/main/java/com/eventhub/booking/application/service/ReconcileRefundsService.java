package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.application.port.in.ReconcileReport;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ChargeRequest;
import com.eventhub.booking.application.port.out.ChargeResult;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.PaymentGatewayException;
import com.eventhub.booking.application.port.out.ReconciliationRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.Booking.CaptureOutcome;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Finishes what no request is going to finish:
 *
 * <ul>
 *   <li>payments REFUND_PENDING (a refund that failed, or one left by the cancellation cascade):
 *       the refund is tried again;
 *   <li>payments left INITIATED for longer than the threshold (the process died after charging, or
 *       the gateway timed out): the charge is repeated, which is idempotent by payment id, and the
 *       result is applied exactly as if the original request had received it: CONFIRMED if the
 *       booking is still PENDING and valid, otherwise a refund.
 * </ul>
 *
 * Every failed attempt is recorded and the payment is not tried again until its backoff elapsed.
 */
public class ReconcileRefundsService implements ReconcileRefundsUseCase {

  private final BookingRepository bookings;
  private final ReconciliationRepository reconciliation;
  private final PaymentGateway gateway;
  private final Clock clock;
  private final ReconcilerPolicy policy;
  private final PaymentOutcomes outcomes;
  private final RefundSettler refunds;

  public ReconcileRefundsService(
      TransactionRunner transactions,
      BookingRepository bookings,
      ReconciliationRepository reconciliation,
      DomainEventPublisher publisher,
      PaymentGateway gateway,
      Clock clock,
      ReconcilerPolicy policy) {
    this.bookings = bookings;
    this.reconciliation = reconciliation;
    this.gateway = gateway;
    this.clock = clock;
    this.policy = policy;
    this.outcomes = new PaymentOutcomes(transactions, bookings, publisher, clock);
    this.refunds = new RefundSettler(transactions, bookings, publisher, gateway, clock);
  }

  @Override
  public ReconcileReport reconcile() {
    Instant now = clock.instant();
    Tally tally = new Tally();
    for (UUID bookingId : reconciliation.findRefundsDue(now, policy.batchSize())) {
      settleRefund(bookingId, tally);
    }
    Instant staleBefore = now.minus(policy.initiatedStaleAfter());
    for (UUID bookingId :
        reconciliation.findStaleInitiatedDue(staleBefore, now, policy.batchSize())) {
      resolveOrphan(bookingId, tally);
    }
    return tally.report();
  }

  private void settleRefund(UUID bookingId, Tally tally) {
    Optional<Payment> payment = bookings.findById(bookingId).flatMap(Booking::payment);
    if (payment.isEmpty()) {
      return;
    }
    if (refunds.settle(bookingId)) {
      tally.refundsCompleted++;
    } else {
      tally.refundsFailed++;
      backOff(payment.get().id(), "refund not accepted by the gateway");
    }
  }

  private void resolveOrphan(UUID bookingId, Tally tally) {
    Optional<Payment> initiated =
        bookings
            .findById(bookingId)
            .flatMap(Booking::payment)
            .filter(payment -> payment.status() == PaymentStatus.INITIATED);
    if (initiated.isEmpty()) {
      return;
    }
    Payment payment = initiated.get();
    ChargeResult result;
    try {
      result =
          gateway.charge(
              new ChargeRequest(
                  payment.id(), payment.amount(), payment.paymentToken().orElseThrow()));
    } catch (PaymentGatewayException unknownAgain) {
      tally.orphansUnresolved++;
      backOff(payment.id(), "charge outcome still unknown");
      return;
    }
    switch (result) {
      case ChargeResult.Approved approved -> applyApproval(bookingId, payment.id(), tally);
      case ChargeResult.Declined declined -> {
        outcomes.decline(bookingId, payment.id());
        tally.orphansDeclined++;
      }
    }
  }

  private void applyApproval(UUID bookingId, UUID paymentId, Tally tally) {
    PaymentOutcomes.Captured captured = outcomes.capture(bookingId, paymentId);
    if (captured.outcome() == CaptureOutcome.CONFIRMED) {
      tally.orphansConfirmed++;
      return;
    }
    tally.orphansRefunded++;
    if (!refunds.settle(bookingId)) {
      backOff(paymentId, "refund not accepted by the gateway");
    }
  }

  private void backOff(UUID paymentId, String error) {
    int failures = reconciliation.failedAttempts(paymentId);
    reconciliation.recordFailure(
        paymentId, error, clock.instant().plus(policy.backoffAfter(failures)));
  }

  private static final class Tally {
    int refundsCompleted;
    int refundsFailed;
    int orphansConfirmed;
    int orphansRefunded;
    int orphansDeclined;
    int orphansUnresolved;

    ReconcileReport report() {
      return new ReconcileReport(
          refundsCompleted,
          refundsFailed,
          orphansConfirmed,
          orphansRefunded,
          orphansDeclined,
          orphansUnresolved);
    }
  }
}
