package com.eventhub.booking.support;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The messages catalog-service puts on the exchange, built the way its outbox builds them (envelope
 * v1, the shape of {@code contracts/events}), including the shape of a re-emission: a cancelled
 * event is replayed as an {@code EventPublished} followed by an {@code EventCancelled}, both with
 * the same {@code occurredAt}. A contract test checks these against the catalog JSON Schemas.
 */
public final class CatalogPayloads {

  public static final String PUBLISHED_KEY = "catalog.event.published.v1";
  public static final String RESCHEDULED_KEY = "catalog.event.rescheduled.v1";
  public static final String CANCELLED_KEY = "catalog.event.cancelled.v1";

  public static final JsonMapper JSON = JsonMapper.builder().build();

  /** A zone as catalog publishes it. */
  public record Zone(UUID zoneId, String name, int capacity, String price) {}

  private CatalogPayloads() {}

  public static String published(
      UUID messageId,
      UUID eventId,
      Instant occurredAt,
      Instant startsAt,
      Instant endsAt,
      List<Zone> zones) {
    ObjectNode data = JSON.createObjectNode();
    data.put("eventId", eventId.toString());
    data.put("organizerId", UUID.randomUUID().toString());
    data.put("title", "Rock Fest");
    data.put("venue", "Estadio Nacional");
    data.put("startsAt", iso(startsAt));
    data.put("endsAt", iso(endsAt));
    ArrayNode zonesNode = data.putArray("zones");
    for (Zone zone : zones) {
      ObjectNode node = zonesNode.addObject();
      node.put("zoneId", zone.zoneId().toString());
      node.put("name", zone.name());
      node.put("capacity", zone.capacity());
      ObjectNode price = node.putObject("price");
      price.put("amount", zone.price());
      price.put("currency", "PEN");
    }
    return envelope("EventPublished", messageId, occurredAt, data);
  }

  public static String rescheduled(
      UUID messageId, UUID eventId, Instant occurredAt, Instant startsAt, Instant endsAt) {
    ObjectNode data = JSON.createObjectNode();
    data.put("eventId", eventId.toString());
    data.put("startsAt", iso(startsAt));
    data.put("endsAt", iso(endsAt));
    data.put("venue", "Estadio Nacional");
    return envelope("EventRescheduled", messageId, occurredAt, data);
  }

  public static String cancelled(UUID messageId, UUID eventId, Instant occurredAt, String reason) {
    ObjectNode data = JSON.createObjectNode();
    data.put("eventId", eventId.toString());
    data.put("reason", reason);
    data.put("cancelledAt", iso(occurredAt));
    return envelope("EventCancelled", messageId, occurredAt, data);
  }

  /** Any envelope, for the tests that need something catalog would never send. */
  public static String envelope(String type, UUID messageId, Instant occurredAt, ObjectNode data) {
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("messageId", messageId.toString());
    envelope.put("type", type);
    envelope.put("version", 1);
    envelope.put("occurredAt", iso(occurredAt));
    envelope.put("correlationId", UUID.randomUUID().toString());
    envelope.put("producer", "catalog-service");
    envelope.set("data", data);
    return JSON.writeValueAsString(envelope);
  }

  /** The JSON with one change applied, to build a message that is wrong in one way. */
  public static String mutated(String json, java.util.function.Consumer<ObjectNode> change) {
    ObjectNode node = (ObjectNode) JSON.readTree(json);
    change.accept(node);
    return JSON.writeValueAsString(node);
  }

  private static String iso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant);
  }
}
