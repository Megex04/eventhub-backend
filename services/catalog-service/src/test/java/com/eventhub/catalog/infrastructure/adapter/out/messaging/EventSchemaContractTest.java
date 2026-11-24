package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.domain.DomainEvent;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.support.EventSchemas;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contract test of the producer: what the service really emits, built from the real aggregate and
 * the real envelope factory, validates against the JSON Schemas of {@code contracts/events}. The
 * negative cases prove the validator bites (formats included), so a green run means something.
 */
class EventSchemaContractTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final JsonMapper json = JsonMapper.builder().build();
  private final OutboxEnvelopeFactory factory = new OutboxEnvelopeFactory(json);

  private Event publishedEvent() {
    Instant startsAt = NOW.plus(Duration.ofDays(30));
    TicketZone general =
        TicketZone.create(UUID.randomUUID(), "General", Money.of("150.00", "PEN"), 100);
    TicketZone vip = TicketZone.create(UUID.randomUUID(), "VIP", Money.of("450", "PEN"), 20);
    Event event =
        Event.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Rock Fest",
            "Open air",
            "Estadio Nacional",
            startsAt,
            startsAt.plus(Duration.ofHours(4)),
            List.of(general, vip),
            CLOCK);
    event.publish(CLOCK);
    return event;
  }

  /** The envelope exactly as the outbox stores and the relay sends it. */
  private String envelopeOf(DomainEvent domainEvent) {
    return factory.create(domainEvent, UUID.randomUUID(), UUID.randomUUID(), NOW).payload();
  }

  private List<String> violationsOf(String type, ObjectNode envelope) {
    return EventSchemas.violations(type, json.writeValueAsString(envelope));
  }

  private ObjectNode mutableEnvelopeOfPublished() {
    Event event = publishedEvent();
    return (ObjectNode) json.readTree(envelopeOf(event.pullDomainEvents().getFirst()));
  }

  @Test
  void should_satisfy_the_published_schema_when_an_event_is_published() {
    Event event = publishedEvent();

    assertThat(
            EventSchemas.violations(
                "EventPublished", envelopeOf(event.pullDomainEvents().getFirst())))
        .isEmpty();
  }

  @Test
  void should_satisfy_the_cancelled_schema_when_a_published_event_is_cancelled() {
    Event event = publishedEvent();
    event.pullDomainEvents();
    event.cancel("Venue unavailable", CLOCK);

    assertThat(
            EventSchemas.violations(
                "EventCancelled", envelopeOf(event.pullDomainEvents().getFirst())))
        .isEmpty();
  }

  @Test
  void should_satisfy_the_rescheduled_schema_when_a_published_event_is_moved() {
    Event event = publishedEvent();
    event.pullDomainEvents();
    Instant newStart = NOW.plus(Duration.ofDays(45));
    event.reschedule(newStart, newStart.plus(Duration.ofHours(3)), "Arena Lima", CLOCK);

    assertThat(
            EventSchemas.violations(
                "EventRescheduled", envelopeOf(event.pullDomainEvents().getFirst())))
        .isEmpty();
  }

  @Test
  void should_reject_the_payload_when_it_is_a_different_event_type() {
    Event event = publishedEvent();
    event.pullDomainEvents();
    event.cancel("Venue unavailable", CLOCK);
    String cancelled = envelopeOf(event.pullDomainEvents().getFirst());

    assertThat(EventSchemas.violations("EventPublished", cancelled)).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"messageId", "type", "version", "occurredAt", "correlationId", "producer", "data"})
  void should_reject_the_envelope_when_a_required_member_is_missing(String member) {
    ObjectNode envelope = mutableEnvelopeOfPublished();
    envelope.remove(member);

    assertThat(violationsOf("EventPublished", envelope)).isNotEmpty();
  }

  @Test
  void should_reject_the_envelope_when_the_message_id_is_not_a_uuid() {
    ObjectNode envelope = mutableEnvelopeOfPublished();
    envelope.put("messageId", "not-a-uuid");

    assertThat(violationsOf("EventPublished", envelope)).isNotEmpty();
  }

  @Test
  void should_reject_the_envelope_when_occurred_at_is_not_a_timestamp() {
    ObjectNode envelope = mutableEnvelopeOfPublished();
    envelope.put("occurredAt", "yesterday");

    assertThat(violationsOf("EventPublished", envelope)).isNotEmpty();
  }

  @Test
  void should_reject_the_envelope_when_the_producer_is_another_service() {
    ObjectNode envelope = mutableEnvelopeOfPublished();
    envelope.put("producer", "booking-service");

    assertThat(violationsOf("EventPublished", envelope)).isNotEmpty();
  }

  @Test
  void should_reject_the_data_when_a_price_has_the_wrong_number_of_decimals() {
    ObjectNode envelope = mutableEnvelopeOfPublished();
    JsonNode price = envelope.get("data").get("zones").get(0).get("price");
    ((ObjectNode) price).put("amount", "150.5");

    assertThat(violationsOf("EventPublished", envelope)).isNotEmpty();
  }

  @Test
  void should_keep_money_as_two_decimal_text_when_a_zone_price_is_a_whole_number() {
    ObjectNode envelope = mutableEnvelopeOfPublished();

    assertThat(envelope.get("data").get("zones").get(1).get("price").get("amount").asString())
        .isEqualTo("450.00");
  }
}
