package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import com.eventhub.catalog.domain.DomainEvent;
import com.eventhub.catalog.domain.EventCancelled;
import com.eventhub.catalog.domain.EventPublished;
import com.eventhub.catalog.domain.EventRescheduled;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a domain event into the envelope v1 defined in {@code contracts/events}. The shape of
 * {@code data} for each type mirrors its JSON Schema.
 */
final class OutboxEnvelopeFactory {

  static final String PRODUCER = "catalog-service";
  static final String AGGREGATE_TYPE = "Event";
  static final int VERSION = 1;

  private final JsonMapper json;

  OutboxEnvelopeFactory(JsonMapper json) {
    this.json = json;
  }

  OutboxMessage create(DomainEvent event, UUID messageId, UUID correlationId, Instant createdAt) {
    String type = typeOf(event);
    ObjectNode envelope = json.createObjectNode();
    envelope.put("messageId", messageId.toString());
    envelope.put("type", type);
    envelope.put("version", VERSION);
    envelope.put("occurredAt", iso(event.occurredAt()));
    envelope.put("correlationId", correlationId.toString());
    envelope.put("producer", PRODUCER);
    envelope.set("data", dataOf(event));
    return new OutboxMessage(
        messageId,
        AGGREGATE_TYPE,
        event.eventId(),
        type,
        VERSION,
        json.writeValueAsString(envelope),
        correlationId,
        createdAt);
  }

  private static String typeOf(DomainEvent event) {
    return switch (event) {
      case EventPublished e -> "EventPublished";
      case EventCancelled e -> "EventCancelled";
      case EventRescheduled e -> "EventRescheduled";
    };
  }

  private ObjectNode dataOf(DomainEvent event) {
    return switch (event) {
      case EventPublished e -> published(e);
      case EventCancelled e -> cancelled(e);
      case EventRescheduled e -> rescheduled(e);
    };
  }

  private ObjectNode published(EventPublished event) {
    ObjectNode data = json.createObjectNode();
    data.put("eventId", event.eventId().toString());
    data.put("organizerId", event.organizerId().toString());
    data.put("title", event.title());
    data.put("venue", event.venue());
    data.put("startsAt", iso(event.startsAt()));
    data.put("endsAt", iso(event.endsAt()));
    ArrayNode zones = data.putArray("zones");
    for (EventPublished.Zone zone : event.zones()) {
      ObjectNode node = zones.addObject();
      node.put("zoneId", zone.zoneId().toString());
      node.put("name", zone.name());
      node.put("capacity", zone.capacity());
      ObjectNode price = node.putObject("price");
      price.put("amount", zone.price().amount().toPlainString());
      price.put("currency", zone.price().currency());
    }
    return data;
  }

  private ObjectNode cancelled(EventCancelled event) {
    ObjectNode data = json.createObjectNode();
    data.put("eventId", event.eventId().toString());
    data.put("reason", event.reason());
    data.put("cancelledAt", iso(event.occurredAt()));
    return data;
  }

  private ObjectNode rescheduled(EventRescheduled event) {
    ObjectNode data = json.createObjectNode();
    data.put("eventId", event.eventId().toString());
    data.put("startsAt", iso(event.startsAt()));
    data.put("endsAt", iso(event.endsAt()));
    data.put("venue", event.venue());
    return data;
  }

  private static String iso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant);
  }
}
