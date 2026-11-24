package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.domain.DomainEvent;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OutboxEnvelopeFactoryTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00.123456Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID MESSAGE_ID = new UUID(2, 1);
  private static final UUID CORRELATION_ID = new UUID(2, 2);
  private static final Instant STARTS_AT = Instant.parse("2026-07-01T20:00:00Z");

  private final JsonMapper json = JsonMapper.builder().build();
  private final OutboxEnvelopeFactory factory = new OutboxEnvelopeFactory(json);

  private Event draftWithZone() {
    return Event.create(
        UUID.randomUUID(),
        new UUID(1, 1),
        "Rock Fest",
        "",
        "Estadio Nacional",
        STARTS_AT,
        STARTS_AT.plus(Duration.ofHours(4)),
        List.of(TicketZone.create(new UUID(3, 1), "General", Money.of("100", "PEN"), 500)),
        CLOCK);
  }

  private OutboxMessage messageFor(DomainEvent event) {
    return factory.create(event, MESSAGE_ID, CORRELATION_ID, NOW);
  }

  private JsonNode payloadOf(OutboxMessage message) {
    return json.readTree(message.payload());
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  @Test
  void should_wrap_event_published_in_envelope_v1_when_it_is_converted() {
    Event event = draftWithZone();
    event.publish(CLOCK);

    OutboxMessage message = messageFor(event.pullDomainEvents().getFirst());
    JsonNode envelope = payloadOf(message);

    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder(
            "messageId", "type", "version", "occurredAt", "correlationId", "producer", "data");
    assertThat(envelope.get("messageId").asString()).isEqualTo(MESSAGE_ID.toString());
    assertThat(envelope.get("type").asString()).isEqualTo("EventPublished");
    assertThat(envelope.get("version").asInt()).isEqualTo(1);
    assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-06-01T12:00:00.123456Z");
    assertThat(envelope.get("correlationId").asString()).isEqualTo(CORRELATION_ID.toString());
    assertThat(envelope.get("producer").asString()).isEqualTo("catalog-service");
  }

  @Test
  void should_describe_the_row_when_event_published_is_converted() {
    Event event = draftWithZone();
    event.publish(CLOCK);

    OutboxMessage message = messageFor(event.pullDomainEvents().getFirst());

    assertThat(message.id()).isEqualTo(MESSAGE_ID);
    assertThat(message.aggregateType()).isEqualTo("Event");
    assertThat(message.aggregateId()).isEqualTo(event.id());
    assertThat(message.eventType()).isEqualTo("EventPublished");
    assertThat(message.eventVersion()).isEqualTo(1);
    assertThat(message.correlationId()).isEqualTo(CORRELATION_ID);
    assertThat(message.createdAt()).isEqualTo(NOW);
  }

  @Test
  void should_include_exactly_the_contract_fields_when_event_published_data_is_built() {
    Event event = draftWithZone();
    event.publish(CLOCK);

    JsonNode data = payloadOf(messageFor(event.pullDomainEvents().getFirst())).get("data");

    assertThat(fieldNames(data))
        .containsExactlyInAnyOrder(
            "eventId", "organizerId", "title", "venue", "startsAt", "endsAt", "zones");
    assertThat(data.get("eventId").asString()).isEqualTo(event.id().toString());
    assertThat(data.get("organizerId").asString()).isEqualTo(new UUID(1, 1).toString());
    assertThat(data.get("title").asString()).isEqualTo("Rock Fest");
    assertThat(data.get("venue").asString()).isEqualTo("Estadio Nacional");
    assertThat(data.get("startsAt").asString()).isEqualTo("2026-07-01T20:00:00Z");
    assertThat(data.get("endsAt").asString()).isEqualTo("2026-07-02T00:00:00Z");
  }

  @Test
  void should_send_prices_as_two_decimal_strings_when_zones_are_serialized() {
    Event event = draftWithZone();
    event.publish(CLOCK);

    JsonNode zone =
        payloadOf(messageFor(event.pullDomainEvents().getFirst())).get("data").get("zones").get(0);

    assertThat(fieldNames(zone)).containsExactlyInAnyOrder("zoneId", "name", "capacity", "price");
    assertThat(zone.get("zoneId").asString()).isEqualTo(new UUID(3, 1).toString());
    assertThat(zone.get("name").asString()).isEqualTo("General");
    assertThat(zone.get("capacity").asInt()).isEqualTo(500);
    assertThat(fieldNames(zone.get("price"))).containsExactlyInAnyOrder("amount", "currency");
    assertThat(zone.get("price").get("amount").isString()).isTrue();
    assertThat(zone.get("price").get("amount").asString()).isEqualTo("100.00");
    assertThat(zone.get("price").get("currency").asString()).isEqualTo("PEN");
  }

  @Test
  void should_convert_event_cancelled_with_reason_and_cancellation_time() {
    Event event = draftWithZone();
    event.publish(CLOCK);
    event.cancel("Venue unavailable", CLOCK);
    DomainEvent cancelled = event.pullDomainEvents().getLast();

    JsonNode envelope = payloadOf(messageFor(cancelled));

    assertThat(envelope.get("type").asString()).isEqualTo("EventCancelled");
    assertThat(fieldNames(envelope.get("data")))
        .containsExactlyInAnyOrder("eventId", "reason", "cancelledAt");
    assertThat(envelope.get("data").get("reason").asString()).isEqualTo("Venue unavailable");
    assertThat(envelope.get("data").get("cancelledAt").asString())
        .isEqualTo("2026-06-01T12:00:00.123456Z");
  }

  @Test
  void should_convert_event_rescheduled_with_the_resulting_venue() {
    Event event = draftWithZone();
    event.publish(CLOCK);
    event.reschedule(STARTS_AT.plus(Duration.ofDays(7)), STARTS_AT.plus(Duration.ofDays(8)), CLOCK);
    DomainEvent rescheduled = event.pullDomainEvents().getLast();

    JsonNode envelope = payloadOf(messageFor(rescheduled));

    assertThat(envelope.get("type").asString()).isEqualTo("EventRescheduled");
    assertThat(fieldNames(envelope.get("data")))
        .containsExactlyInAnyOrder("eventId", "startsAt", "endsAt", "venue");
    assertThat(envelope.get("data").get("venue").asString()).isEqualTo("Estadio Nacional");
    assertThat(envelope.get("data").get("startsAt").asString()).isEqualTo("2026-07-08T20:00:00Z");
  }
}
