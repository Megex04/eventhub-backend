package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.domain.PaymentStatus;
import com.eventhub.booking.domain.RefundReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One charge attempt. Hibernate raises {@code version} by one for every change of the row. {@code
 * attempts}, {@code next_attempt_at} and {@code last_error} are written by the reconciler with SQL
 * and are deliberately not mapped here, so saving a payment can never overwrite them with stale
 * values.
 */
@Entity
@Table(name = "payment")
class PaymentEntity {

  @Id private UUID id;

  @Column(name = "booking_id", nullable = false, updatable = false)
  private UUID bookingId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PaymentStatus status;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "payment_token", length = 80)
  private String paymentToken;

  @Enumerated(EnumType.STRING)
  @Column(name = "refund_reason", length = 40)
  private RefundReason refundReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private long version;

  protected PaymentEntity() {}

  PaymentEntity(
      UUID id,
      UUID bookingId,
      PaymentStatus status,
      BigDecimal amount,
      String currency,
      String paymentToken,
      RefundReason refundReason,
      Instant createdAt) {
    this.id = id;
    this.bookingId = bookingId;
    this.status = status;
    this.amount = amount;
    this.currency = currency;
    this.paymentToken = paymentToken;
    this.refundReason = refundReason;
    this.createdAt = createdAt;
  }

  void applyState(PaymentStatus status, String paymentToken, RefundReason refundReason) {
    this.status = status;
    this.paymentToken = paymentToken;
    this.refundReason = refundReason;
  }

  UUID getId() {
    return id;
  }

  UUID getBookingId() {
    return bookingId;
  }

  PaymentStatus getStatus() {
    return status;
  }

  BigDecimal getAmount() {
    return amount;
  }

  String getCurrency() {
    return currency;
  }

  String getPaymentToken() {
    return paymentToken;
  }

  RefundReason getRefundReason() {
    return refundReason;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  long getVersion() {
    return version;
  }
}
