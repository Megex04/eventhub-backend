package com.eventhub.booking.domain;

import com.eventhub.booking.domain.BusinessRuleViolationException.Rule;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root. Statuses move only {@code PENDING -> CONFIRMED | EXPIRED | CANCELLED} and {@code
 * CONFIRMED -> CANCELLED}; EXPIRED and CANCELLED are terminal. Checks run in a fixed order so
 * callers get a predictable error: field values (400), then lifecycle state (409), then business
 * rules (422).
 *
 * <p>The hold is half-open: a booking is payable while {@code now < expiresAt} and expirable once
 * {@code now >= expiresAt}. Releasing inventory is the application's job, done in the same
 * transaction as the transition that justifies it.
 */
public final class Booking {

  /** What the charge of a payment means for the booking once the gateway approved it. */
  public enum CaptureOutcome {
    /** The booking was still payable: it is now CONFIRMED. */
    CONFIRMED,
    /** The booking stopped being payable meanwhile: the money must be given back. */
    REFUND_REQUIRED
  }

  private final UUID id;
  private final UUID userId;
  private final UUID eventId;
  private BookingStatus status;
  private final List<BookingItem> items;
  private final Money total;
  private final Instant createdAt;
  private final Instant expiresAt;
  private Instant confirmedAt;
  private Instant expiredAt;
  private Instant cancelledAt;
  private CancelReason cancelReason;
  private Payment payment;
  // Optimistic-locking token owned by persistence: read-only here, never changed by the domain.
  private final long version;
  private final List<DomainEvent> pendingEvents = new ArrayList<>();

  private Booking(
      UUID id,
      UUID userId,
      UUID eventId,
      BookingStatus status,
      List<BookingItem> items,
      Money total,
      Instant createdAt,
      Instant expiresAt,
      Instant confirmedAt,
      Instant expiredAt,
      Instant cancelledAt,
      CancelReason cancelReason,
      Payment payment,
      long version) {
    this.id = id;
    this.userId = userId;
    this.eventId = eventId;
    this.status = status;
    this.items = List.copyOf(items);
    this.total = total;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.confirmedAt = confirmedAt;
    this.expiredAt = expiredAt;
    this.cancelledAt = cancelledAt;
    this.cancelReason = cancelReason;
    this.payment = payment;
    this.version = version;
  }

  /**
   * Creates a PENDING booking priced from the projection and holds it for the policy's duration.
   */
  public static Booking create(
      UUID id,
      UUID userId,
      EventProjection event,
      List<RequestedItem> requested,
      BookingPolicy policy,
      Clock clock) {
    Validation.requiredId("id", id);
    Validation.requiredId("userId", userId);
    Validation.required("event", event);
    Validation.required("policy", policy);
    if (Validation.required("items", requested).isEmpty()) {
      throw new InvalidValueException("items", "items must not be empty");
    }
    requireOnSale(event, clock);
    requireDistinctZones(requested);
    List<BookingItem> items = priceItems(event, requested);
    requireWithinMaxTickets(requested, policy);
    Money total = totalOf(items);
    Instant now = clock.instant();
    Booking booking =
        new Booking(
            id,
            userId,
            event.eventId(),
            BookingStatus.PENDING,
            items,
            total,
            now,
            now.plus(policy.holdDuration()),
            null,
            null,
            null,
            null,
            null,
            0L);
    booking.pendingEvents.add(
        new BookingCreated(id, userId, event.eventId(), items, total, booking.expiresAt, now));
    return booking;
  }

  /** Rebuilds an aggregate from persisted state without re-validating or recording events. */
  public static Booking restore(
      UUID id,
      UUID userId,
      UUID eventId,
      BookingStatus status,
      List<BookingItem> items,
      Money total,
      Instant createdAt,
      Instant expiresAt,
      Optional<Instant> confirmedAt,
      Optional<Instant> expiredAt,
      Optional<Instant> cancelledAt,
      Optional<CancelReason> cancelReason,
      Optional<Payment> payment,
      long version) {
    return new Booking(
        id,
        userId,
        eventId,
        status,
        items,
        total,
        createdAt,
        expiresAt,
        confirmedAt.orElse(null),
        expiredAt.orElse(null),
        cancelledAt.orElse(null),
        cancelReason.orElse(null),
        payment.orElse(null),
        version);
  }

  /**
   * Records the intention to charge: a payment INITIATED for exactly the total. Fails if the hold
   * ended, or if an attempt is still active; a DECLINED one does not block a new attempt.
   */
  public Payment beginPayment(UUID paymentId, Clock clock) {
    Validation.requiredId("paymentId", paymentId);
    requireStatus("pay", BookingStatus.PENDING);
    if (!clock.instant().isBefore(expiresAt)) {
      throw InvalidStateException.holdEnded(status);
    }
    if (payment != null && payment.isActive()) {
      throw new PaymentInProgressException(payment.id());
    }
    this.payment = Payment.initiate(paymentId, total);
    return payment;
  }

  /** The gateway refused the charge: the booking stays PENDING and can be paid again. */
  public void declinePayment() {
    this.payment = requirePayment().decline();
  }

  /**
   * The gateway approved the charge. The money is taken whatever happened to the booking meanwhile,
   * so the payment is CAPTURED first; the booking is then CONFIRMED if it is still payable, or the
   * payment moves to REFUND_PENDING with the reason why.
   */
  public CaptureOutcome capturePayment(Clock clock) {
    Payment captured = requirePayment().capture();
    Instant now = clock.instant();
    if (status == BookingStatus.PENDING && now.isBefore(expiresAt)) {
      this.payment = captured;
      this.status = BookingStatus.CONFIRMED;
      this.confirmedAt = now;
      pendingEvents.add(new BookingConfirmed(id, userId, eventId, captured.id(), total, now));
      return CaptureOutcome.CONFIRMED;
    }
    this.payment = captured.requestRefund(lateCaptureReason());
    return CaptureOutcome.REFUND_REQUIRED;
  }

  /** The gateway returned the money: REFUND_PENDING -> REFUNDED. */
  public void completeRefund(Clock clock) {
    Payment refunded = requirePayment().completeRefund();
    this.payment = refunded;
    pendingEvents.add(
        new BookingPaymentRefunded(
            id,
            userId,
            eventId,
            refunded.id(),
            refunded.amount(),
            refunded.refundReason().orElseThrow(),
            clock.instant()));
  }

  public boolean isExpirable(Clock clock) {
    return status == BookingStatus.PENDING && !clock.instant().isBefore(expiresAt);
  }

  public void expire(Clock clock) {
    requireStatus("expire", BookingStatus.PENDING);
    Instant now = clock.instant();
    if (now.isBefore(expiresAt)) {
      throw InvalidStateException.holdStillActive(status);
    }
    this.status = BookingStatus.EXPIRED;
    this.expiredAt = now;
    pendingEvents.add(new BookingExpired(id, userId, eventId, now));
  }

  /**
   * Cancels a PENDING or CONFIRMED booking. A CONFIRMED one opens the refund of its payment. The
   * customer cannot cancel a CONFIRMED booking once the event started; a cancellation caused by the
   * event itself always goes through.
   */
  public void cancel(CancelReason reason, Instant eventStartsAt, Clock clock) {
    Validation.required("reason", reason);
    Validation.required("eventStartsAt", eventStartsAt);
    requireStatus("cancel", BookingStatus.PENDING, BookingStatus.CONFIRMED);
    Instant now = clock.instant();
    boolean wasConfirmed = status == BookingStatus.CONFIRMED;
    if (wasConfirmed && reason == CancelReason.USER_REQUEST && !now.isBefore(eventStartsAt)) {
      throw new BusinessRuleViolationException(
          Rule.CANCEL_AFTER_START,
          "A confirmed booking cannot be cancelled once the event started");
    }
    if (wasConfirmed) {
      this.payment = requirePayment().requestRefund(refundReasonFor(reason));
    }
    this.status = BookingStatus.CANCELLED;
    this.cancelledAt = now;
    this.cancelReason = reason;
    pendingEvents.add(new BookingCancelled(id, userId, eventId, reason, now));
  }

  public List<DomainEvent> pullDomainEvents() {
    List<DomainEvent> events = List.copyOf(pendingEvents);
    pendingEvents.clear();
    return events;
  }

  public UUID id() {
    return id;
  }

  public UUID userId() {
    return userId;
  }

  public UUID eventId() {
    return eventId;
  }

  public BookingStatus status() {
    return status;
  }

  public List<BookingItem> items() {
    return items;
  }

  public Money total() {
    return total;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<Instant> confirmedAt() {
    return Optional.ofNullable(confirmedAt);
  }

  public Optional<Instant> expiredAt() {
    return Optional.ofNullable(expiredAt);
  }

  public Optional<Instant> cancelledAt() {
    return Optional.ofNullable(cancelledAt);
  }

  public Optional<CancelReason> cancelReason() {
    return Optional.ofNullable(cancelReason);
  }

  public Optional<Payment> payment() {
    return Optional.ofNullable(payment);
  }

  public long version() {
    return version;
  }

  private RefundReason lateCaptureReason() {
    if (status != BookingStatus.CANCELLED) {
      return RefundReason.BOOKING_EXPIRED_DURING_PAYMENT;
    }
    return refundReasonFor(cancelReason);
  }

  private static RefundReason refundReasonFor(CancelReason reason) {
    return switch (reason) {
      case USER_REQUEST -> RefundReason.BOOKING_CANCELLED;
      case EVENT_CANCELLED -> RefundReason.EVENT_CANCELLED;
    };
  }

  private Payment requirePayment() {
    if (payment == null) {
      throw InvalidStateException.noPayment(status);
    }
    return payment;
  }

  private void requireStatus(String action, BookingStatus first, BookingStatus... others) {
    if (status != first && !List.of(others).contains(status)) {
      throw InvalidStateException.notAllowed("booking", action, status);
    }
  }

  private static void requireOnSale(EventProjection event, Clock clock) {
    if (!event.isOnSale(clock)) {
      throw new BusinessRuleViolationException(
          Rule.EVENT_NOT_ON_SALE, "The event is cancelled or has already started");
    }
  }

  private static void requireDistinctZones(List<RequestedItem> requested) {
    Set<UUID> seen = new HashSet<>();
    for (RequestedItem item : requested) {
      if (!seen.add(item.zoneId())) {
        throw new BusinessRuleViolationException(
            Rule.DUPLICATE_ZONE, "Zone %s appears more than once".formatted(item.zoneId()));
      }
    }
  }

  private static List<BookingItem> priceItems(
      EventProjection event, List<RequestedItem> requested) {
    return requested.stream()
        .map(item -> {
          ProjectedZone zone =
              event
                  .zone(item.zoneId())
                  .orElseThrow(() ->
                      new BusinessRuleViolationException(
                          Rule.ZONE_NOT_IN_EVENT,
                          "Zone %s does not belong to the event".formatted(item.zoneId())));
          return new BookingItem(zone.zoneId(), zone.name(), item.quantity(), zone.price());
        })
        .toList();
  }

  private static void requireWithinMaxTickets(List<RequestedItem> requested, BookingPolicy policy) {
    int tickets = requested.stream().mapToInt(RequestedItem::quantity).sum();
    if (tickets > policy.maxTickets()) {
      throw new BusinessRuleViolationException(
          Rule.MAX_TICKETS_EXCEEDED,
          "A booking holds at most %d tickets, %d requested"
              .formatted(policy.maxTickets(), tickets));
    }
  }

  /** Money.plus raises MIXED_CURRENCY when the zones are priced in different currencies. */
  private static Money totalOf(List<BookingItem> items) {
    return items.stream().map(BookingItem::subtotal).reduce(Money::plus).orElseThrow();
  }
}
