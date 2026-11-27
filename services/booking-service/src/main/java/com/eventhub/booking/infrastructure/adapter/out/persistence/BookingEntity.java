package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.CancelReason;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.OptimisticLock;

/**
 * Persistence shape of the Booking aggregate; the domain model never sees this class.
 *
 * <p>The version is advanced exactly once per save by {@link JpaBookingRepository}, which claims
 * the row before it writes anything (ADR 0004). The mutable columns are therefore excluded from
 * Hibernate's own dirty-driven increment: otherwise a save would bump the version twice. The items
 * never change after the booking is created.
 */
@Entity
@Table(name = "booking")
class BookingEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @OptimisticLock(excluded = true)
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private BookingStatus status;

  @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal totalAmount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @OptimisticLock(excluded = true)
  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @OptimisticLock(excluded = true)
  @Column(name = "expired_at")
  private Instant expiredAt;

  @OptimisticLock(excluded = true)
  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @OptimisticLock(excluded = true)
  @Enumerated(EnumType.STRING)
  @Column(name = "cancel_reason", length = 32)
  private CancelReason cancelReason;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Version private long version;

  @ElementCollection
  @CollectionTable(name = "booking_item", joinColumns = @JoinColumn(name = "booking_id"))
  @OrderBy("position ASC")
  private List<BookingItemEmbeddable> items = new ArrayList<>();

  protected BookingEntity() {}

  BookingEntity(
      UUID id,
      UUID userId,
      UUID eventId,
      BigDecimal totalAmount,
      String currency,
      Instant createdAt,
      Instant expiresAt,
      UUID idempotencyKey,
      String requestFingerprint) {
    this.id = id;
    this.userId = userId;
    this.eventId = eventId;
    this.status = BookingStatus.PENDING;
    this.totalAmount = totalAmount;
    this.currency = currency;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.idempotencyKey = idempotencyKey;
    this.requestFingerprint = requestFingerprint;
  }

  void applyState(
      BookingStatus status,
      Instant confirmedAt,
      Instant expiredAt,
      Instant cancelledAt,
      CancelReason cancelReason) {
    this.status = status;
    this.confirmedAt = confirmedAt;
    this.expiredAt = expiredAt;
    this.cancelledAt = cancelledAt;
    this.cancelReason = cancelReason;
  }

  UUID getId() {
    return id;
  }

  UUID getUserId() {
    return userId;
  }

  UUID getEventId() {
    return eventId;
  }

  BookingStatus getStatus() {
    return status;
  }

  BigDecimal getTotalAmount() {
    return totalAmount;
  }

  String getCurrency() {
    return currency;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }

  Instant getConfirmedAt() {
    return confirmedAt;
  }

  Instant getExpiredAt() {
    return expiredAt;
  }

  Instant getCancelledAt() {
    return cancelledAt;
  }

  CancelReason getCancelReason() {
    return cancelReason;
  }

  String getRequestFingerprint() {
    return requestFingerprint;
  }

  long getVersion() {
    return version;
  }

  List<BookingItemEmbeddable> getItems() {
    return items;
  }
}
