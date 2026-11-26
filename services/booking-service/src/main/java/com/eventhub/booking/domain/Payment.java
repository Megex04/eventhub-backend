package com.eventhub.booking.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * One charge attempt for a booking. Immutable: every transition returns the next state, and the
 * legal moves are INITIATED -> CAPTURED | DECLINED and CAPTURED -> REFUND_PENDING -> REFUNDED.
 * Transitions are package-private because only {@link Booking} drives them; the amount is the
 * booking's total by construction.
 *
 * <p>{@code id} is the idempotency key the payment gateway receives for both charge and refund.
 * {@code paymentToken} is the opaque {@code tok_...} the customer sent (never card data). It is
 * kept only while the payment is INITIATED, so that a charge whose outcome is unknown can be
 * repeated by the reconciler, and it is erased by the transition that ends that need: nothing is
 * charged again afterwards. It must never be logged.
 */
public final class Payment {

  private final UUID id;
  private final PaymentStatus status;
  private final Money amount;
  private final String paymentToken;
  private final RefundReason refundReason;

  private Payment(
      UUID id, PaymentStatus status, Money amount, String paymentToken, RefundReason refundReason) {
    this.id = id;
    this.status = status;
    this.amount = amount;
    this.paymentToken = paymentToken;
    this.refundReason = refundReason;
  }

  static Payment initiate(UUID id, Money amount, String paymentToken) {
    return new Payment(
        Validation.requiredId("id", id),
        PaymentStatus.INITIATED,
        Validation.required("amount", amount),
        Validation.required("paymentToken", paymentToken),
        null);
  }

  /** Rebuilds a payment from persisted state; {@code paymentToken} is null once erased. */
  public static Payment restore(
      UUID id,
      PaymentStatus status,
      Money amount,
      String paymentToken,
      Optional<RefundReason> refundReason) {
    return new Payment(id, status, amount, paymentToken, refundReason.orElse(null));
  }

  Payment capture() {
    requireStatus(PaymentStatus.INITIATED, "capture");
    return next(PaymentStatus.CAPTURED, refundReason);
  }

  Payment decline() {
    requireStatus(PaymentStatus.INITIATED, "decline");
    return next(PaymentStatus.DECLINED, refundReason);
  }

  Payment requestRefund(RefundReason reason) {
    requireStatus(PaymentStatus.CAPTURED, "request a refund of");
    return next(PaymentStatus.REFUND_PENDING, Validation.required("reason", reason));
  }

  Payment completeRefund() {
    requireStatus(PaymentStatus.REFUND_PENDING, "complete the refund of");
    return next(PaymentStatus.REFUNDED, refundReason);
  }

  /** True while the payment blocks a new attempt (the same set as the unique partial index). */
  public boolean isActive() {
    return status == PaymentStatus.INITIATED
        || status == PaymentStatus.CAPTURED
        || status == PaymentStatus.REFUND_PENDING;
  }

  public UUID id() {
    return id;
  }

  public PaymentStatus status() {
    return status;
  }

  public Money amount() {
    return amount;
  }

  /** Present only while the payment is INITIATED. */
  public Optional<String> paymentToken() {
    return Optional.ofNullable(paymentToken);
  }

  public Optional<RefundReason> refundReason() {
    return Optional.ofNullable(refundReason);
  }

  private Payment next(PaymentStatus nextStatus, RefundReason reason) {
    // Every next status is one in which the token is no longer needed.
    return new Payment(id, nextStatus, amount, null, reason);
  }

  private void requireStatus(PaymentStatus expected, String action) {
    if (status != expected) {
      throw InvalidStateException.notAllowed("payment", action, status);
    }
  }
}
