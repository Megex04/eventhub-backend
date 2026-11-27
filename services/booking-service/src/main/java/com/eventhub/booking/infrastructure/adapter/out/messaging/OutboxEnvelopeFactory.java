package com.eventhub.booking.infrastructure.adapter.out.messaging;

import com.eventhub.booking.domain.BookingCancelled;
import com.eventhub.booking.domain.BookingConfirmed;
import com.eventhub.booking.domain.BookingCreated;
import com.eventhub.booking.domain.BookingExpired;
import com.eventhub.booking.domain.BookingItem;
import com.eventhub.booking.domain.BookingPaymentRefunded;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.Money;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a domain event into the envelope v1 defined in {@code contracts/events}. The shape of
 * {@code data} for each type mirrors its JSON Schema (and a contract test checks it against them).
 */
final class OutboxEnvelopeFactory {

  static final String PRODUCER = "booking-service";
  static final String AGGREGATE_TYPE = "Booking";
  static final int VERSION = 1;

  /** What the publisher inserts: the row without its creation instant, which the database fixes. */
  record Row(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      int eventVersion,
      String payload,
      UUID correlationId) {}

  private final JsonMapper json;

  OutboxEnvelopeFactory(JsonMapper json) {
    this.json = json;
  }

  Row create(DomainEvent event, UUID messageId, UUID correlationId) {
    String type = typeOf(event);
    ObjectNode envelope = json.createObjectNode();
    envelope.put("messageId", messageId.toString());
    envelope.put("type", type);
    envelope.put("version", VERSION);
    envelope.put("occurredAt", iso(event.occurredAt()));
    envelope.put("correlationId", correlationId.toString());
    envelope.put("producer", PRODUCER);
    envelope.set("data", dataOf(event));
    return new Row(
        messageId,
        AGGREGATE_TYPE,
        event.bookingId(),
        type,
        VERSION,
        json.writeValueAsString(envelope),
        correlationId);
  }

  private static String typeOf(DomainEvent event) {
    return switch (event) {
      case BookingCreated e -> "BookingCreated";
      case BookingConfirmed e -> "BookingConfirmed";
      case BookingExpired e -> "BookingExpired";
      case BookingCancelled e -> "BookingCancelled";
      case BookingPaymentRefunded e -> "BookingPaymentRefunded";
    };
  }

  private ObjectNode dataOf(DomainEvent event) {
    return switch (event) {
      case BookingCreated e -> created(e);
      case BookingConfirmed e -> confirmed(e);
      case BookingExpired e -> expired(e);
      case BookingCancelled e -> cancelled(e);
      case BookingPaymentRefunded e -> refunded(e);
    };
  }

  private ObjectNode created(BookingCreated event) {
    ObjectNode data = base(event.bookingId(), event.userId(), event.eventId());
    ArrayNode items = data.putArray("items");
    for (BookingItem item : event.items()) {
      ObjectNode node = items.addObject();
      node.put("zoneId", item.zoneId().toString());
      node.put("quantity", item.quantity());
      node.set("unitPrice", money(item.unitPrice()));
    }
    data.set("total", money(event.total()));
    data.put("expiresAt", iso(event.expiresAt()));
    return data;
  }

  private ObjectNode confirmed(BookingConfirmed event) {
    ObjectNode data = base(event.bookingId(), event.userId(), event.eventId());
    data.put("paymentId", event.paymentId().toString());
    data.set("total", money(event.total()));
    data.put("confirmedAt", iso(event.occurredAt()));
    return data;
  }

  private ObjectNode expired(BookingExpired event) {
    ObjectNode data = base(event.bookingId(), event.userId(), event.eventId());
    data.put("expiredAt", iso(event.occurredAt()));
    return data;
  }

  private ObjectNode cancelled(BookingCancelled event) {
    ObjectNode data = base(event.bookingId(), event.userId(), event.eventId());
    data.put("reason", event.reason().name());
    data.put("cancelledAt", iso(event.occurredAt()));
    return data;
  }

  private ObjectNode refunded(BookingPaymentRefunded event) {
    ObjectNode data = base(event.bookingId(), event.userId(), event.eventId());
    data.put("paymentId", event.paymentId().toString());
    data.set("amount", money(event.amount()));
    data.put("reason", event.reason().name());
    data.put("refundedAt", iso(event.occurredAt()));
    return data;
  }

  private ObjectNode base(UUID bookingId, UUID userId, UUID eventId) {
    ObjectNode data = json.createObjectNode();
    data.put("bookingId", bookingId.toString());
    data.put("userId", userId.toString());
    data.put("eventId", eventId.toString());
    return data;
  }

  /** Amounts travel as strings with two decimals, never as numbers. */
  private ObjectNode money(Money money) {
    ObjectNode node = json.createObjectNode();
    node.put("amount", money.amount().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
    node.put("currency", money.currency());
    return node;
  }

  private static String iso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant);
  }
}
