package com.eventhub.booking.infrastructure.adapter.in.messaging;

import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.in.EventPublishedMessage;
import com.eventhub.booking.application.port.in.EventRescheduledMessage;
import com.eventhub.booking.domain.InvalidValueException;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.ProjectedZone;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the catalog events with the tolerance of a good consumer: it takes what it needs and
 * ignores the rest, so catalog may add fields within version 1 without breaking it; it fails (as a
 * {@link PoisonMessageException}) when something it needs is missing or malformed, and when the
 * version or the type is not one it knows. The envelope is the one of {@code contracts/events}
 * (envelope v1).
 */
final class CatalogEventReader {

  static final int SUPPORTED_VERSION = 1;

  /** A catalog event, read and ready for its use case. */
  sealed interface Parsed {

    String type();

    record Published(EventPublishedMessage message) implements Parsed {
      @Override
      public String type() {
        return "EventPublished";
      }
    }

    record Rescheduled(EventRescheduledMessage message) implements Parsed {
      @Override
      public String type() {
        return "EventRescheduled";
      }
    }

    record Cancelled(EventCancelledMessage message) implements Parsed {
      @Override
      public String type() {
        return "EventCancelled";
      }
    }
  }

  private final JsonMapper json;

  CatalogEventReader(JsonMapper json) {
    this.json = json;
  }

  Parsed read(byte[] body) {
    JsonNode root = tree(body);
    UUID messageId = uuid(root, "messageId");
    String type = text(root, "type");
    int version = integer(root, "version");
    Instant occurredAt = instant(root, "occurredAt");
    JsonNode data = object(root, "data");
    if (version != SUPPORTED_VERSION) {
      throw new PoisonMessageException("Unsupported version %d of %s".formatted(version, type));
    }
    try {
      return switch (type) {
        case "EventPublished" -> new Parsed.Published(published(messageId, occurredAt, data));
        case "EventRescheduled" -> new Parsed.Rescheduled(rescheduled(messageId, occurredAt, data));
        case "EventCancelled" ->
            new Parsed.Cancelled(
                new EventCancelledMessage(messageId, uuid(data, "eventId"), occurredAt));
        default -> throw new PoisonMessageException("Unknown event type " + type);
      };
    } catch (InvalidValueException e) {
      throw new PoisonMessageException("Invalid " + type + ": " + e.getMessage(), e);
    }
  }

  private EventPublishedMessage published(UUID messageId, Instant occurredAt, JsonNode data) {
    JsonNode zonesNode = data.get("zones");
    if (zonesNode == null || !zonesNode.isArray() || zonesNode.isEmpty()) {
      throw new PoisonMessageException("zones must be a non-empty array");
    }
    List<ProjectedZone> zones = new ArrayList<>();
    for (JsonNode zone : zonesNode) {
      JsonNode price = object(zone, "price");
      zones.add(
          new ProjectedZone(
              uuid(zone, "zoneId"),
              text(zone, "name"),
              Money.of(text(price, "amount"), text(price, "currency")),
              integer(zone, "capacity")));
    }
    return new EventPublishedMessage(
        messageId,
        uuid(data, "eventId"),
        instant(data, "startsAt"),
        instant(data, "endsAt"),
        zones,
        occurredAt);
  }

  private EventRescheduledMessage rescheduled(UUID messageId, Instant occurredAt, JsonNode data) {
    return new EventRescheduledMessage(
        messageId,
        uuid(data, "eventId"),
        instant(data, "startsAt"),
        instant(data, "endsAt"),
        occurredAt);
  }

  // ---- field access: a missing or malformed required field is poison ----

  private JsonNode tree(byte[] body) {
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException e) {
      throw new PoisonMessageException("The body is not valid JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new PoisonMessageException("The body is not a JSON object");
    }
    return root;
  }

  private static JsonNode required(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      throw new PoisonMessageException("Required field missing: " + field);
    }
    return value;
  }

  private static JsonNode object(JsonNode parent, String field) {
    JsonNode value = required(parent, field);
    if (!value.isObject()) {
      throw new PoisonMessageException(field + " must be an object");
    }
    return value;
  }

  private static String text(JsonNode parent, String field) {
    JsonNode value = required(parent, field);
    if (!value.isString()) {
      throw new PoisonMessageException(field + " must be a string");
    }
    return value.asString();
  }

  private static int integer(JsonNode parent, String field) {
    JsonNode value = required(parent, field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new PoisonMessageException(field + " must be an integer");
    }
    return value.intValue();
  }

  private static UUID uuid(JsonNode parent, String field) {
    String text = text(parent, field);
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      throw new PoisonMessageException(field + " must be a UUID", e);
    }
  }

  private static Instant instant(JsonNode parent, String field) {
    String text = text(parent, field);
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw new PoisonMessageException(field + " must be an ISO-8601 instant", e);
    }
  }
}
