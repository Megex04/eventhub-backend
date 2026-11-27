package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingItem;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.ProjectedZone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Explicit mapping between the domain model and the JPA entities, in both directions. */
final class PersistenceMapper {

  private PersistenceMapper() {}

  // ---- booking ----

  static BookingEntity toNewEntity(Booking booking, IdempotencyRecord idempotency) {
    BookingEntity entity =
        new BookingEntity(
            booking.id(),
            booking.userId(),
            booking.eventId(),
            booking.total().amount(),
            booking.total().currency(),
            booking.createdAt(),
            booking.expiresAt(),
            idempotency.key(),
            idempotency.fingerprint());
    applyState(booking, entity);
    int position = 0;
    for (BookingItem item : booking.items()) {
      entity
          .getItems()
          .add(
              new BookingItemEmbeddable(
                  item.zoneId(),
                  position++,
                  item.zoneName(),
                  item.quantity(),
                  item.unitPrice().amount(),
                  item.unitPrice().currency()));
    }
    return entity;
  }

  static void applyState(Booking booking, BookingEntity entity) {
    entity.applyState(
        booking.status(),
        booking.confirmedAt().orElse(null),
        booking.expiredAt().orElse(null),
        booking.cancelledAt().orElse(null),
        booking.cancelReason().orElse(null));
  }

  static Booking toDomain(BookingEntity entity, Optional<Payment> payment) {
    List<BookingItem> items = new ArrayList<>();
    for (BookingItemEmbeddable item : entity.getItems()) {
      items.add(
          new BookingItem(
              item.getZoneId(),
              item.getZoneName(),
              item.getQuantity(),
              new Money(item.getUnitPriceAmount(), item.getUnitPriceCurrency())));
    }
    return Booking.restore(
        entity.getId(),
        entity.getUserId(),
        entity.getEventId(),
        entity.getStatus(),
        items,
        new Money(entity.getTotalAmount(), entity.getCurrency()),
        entity.getCreatedAt(),
        entity.getExpiresAt(),
        Optional.ofNullable(entity.getConfirmedAt()),
        Optional.ofNullable(entity.getExpiredAt()),
        Optional.ofNullable(entity.getCancelledAt()),
        Optional.ofNullable(entity.getCancelReason()),
        payment,
        entity.getVersion());
  }

  // ---- payment ----

  static PaymentEntity toNewEntity(
      java.util.UUID bookingId, Payment payment, java.time.Instant createdAt) {
    return new PaymentEntity(
        payment.id(),
        bookingId,
        payment.status(),
        payment.amount().amount(),
        payment.amount().currency(),
        payment.paymentToken().orElse(null),
        payment.refundReason().orElse(null),
        createdAt);
  }

  static void applyState(Payment payment, PaymentEntity entity) {
    entity.applyState(
        payment.status(), payment.paymentToken().orElse(null), payment.refundReason().orElse(null));
  }

  static Payment toDomain(PaymentEntity entity) {
    return Payment.restore(
        entity.getId(),
        entity.getStatus(),
        new Money(entity.getAmount(), entity.getCurrency()),
        entity.getPaymentToken(),
        Optional.ofNullable(entity.getRefundReason()));
  }

  // ---- event projection ----

  static List<ProjectedZoneEmbeddable> toEmbeddables(List<ProjectedZone> zones) {
    List<ProjectedZoneEmbeddable> result = new ArrayList<>();
    int position = 0;
    for (ProjectedZone zone : zones) {
      result.add(
          new ProjectedZoneEmbeddable(
              zone.zoneId(),
              position++,
              zone.name(),
              zone.price().amount(),
              zone.price().currency(),
              zone.capacity()));
    }
    return result;
  }

  static void applyState(EventProjection projection, EventProjectionEntity entity) {
    entity.applyState(
        projection.status(),
        projection.startsAt().orElse(null),
        projection.endsAt().orElse(null),
        projection.lastOccurredAt(),
        toEmbeddables(projection.zones()));
  }

  static EventProjection toDomain(EventProjectionEntity entity) {
    List<ProjectedZone> zones = new ArrayList<>();
    for (ProjectedZoneEmbeddable zone : entity.getZones()) {
      zones.add(
          new ProjectedZone(
              zone.getZoneId(),
              zone.getName(),
              new Money(zone.getPriceAmount(), zone.getPriceCurrency()),
              zone.getCapacity()));
    }
    return EventProjection.restore(
        entity.getEventId(),
        entity.getStatus(),
        entity.getStartsAt(),
        entity.getEndsAt(),
        zones,
        entity.getLastOccurredAt());
  }
}
