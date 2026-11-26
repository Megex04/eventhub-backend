package com.eventhub.booking.infrastructure.adapter.in.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static com.eventhub.booking.support.CatalogPayloads.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.port.in.EventCancelledMessage;
import com.eventhub.booking.application.port.in.EventPublishedMessage;
import com.eventhub.booking.application.port.in.EventRescheduledMessage;
import com.eventhub.booking.support.CatalogPayloads;
import com.eventhub.booking.support.CatalogPayloads.Zone;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class CatalogEventReaderTest {

  private static final UUID MESSAGE = uuid(9001);
  private static final UUID EVENT_ID = uuid(9002);
  private static final Instant ENDS = EVENT_STARTS.plus(Duration.ofHours(3));
  private static final List<Zone> ZONES =
      List.of(
          new Zone(VIP_ZONE, "VIP", 20, "150.00"), new Zone(GENERAL_ZONE, "General", 100, "50.00"));

  private final CatalogEventReader reader = new CatalogEventReader(JSON);

  private static String published() {
    return CatalogPayloads.published(MESSAGE, EVENT_ID, NOW, EVENT_STARTS, ENDS, ZONES);
  }

  private CatalogEventReader.Parsed read(String json) {
    return reader.read(json.getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode data(ObjectNode envelope) {
    return (ObjectNode) envelope.get("data");
  }

  private static ObjectNode firstZone(ObjectNode envelope) {
    return (ObjectNode) ((ArrayNode) data(envelope).get("zones")).get(0);
  }

  // ---- what it reads ----

  @Test
  void should_read_a_published_event_with_its_zones_and_prices() {
    var parsed = (CatalogEventReader.Parsed.Published) read(published());

    EventPublishedMessage message = parsed.message();
    assertThat(message.messageId()).isEqualTo(MESSAGE);
    assertThat(message.eventId()).isEqualTo(EVENT_ID);
    assertThat(message.startsAt()).isEqualTo(EVENT_STARTS);
    assertThat(message.endsAt()).isEqualTo(ENDS);
    assertThat(message.occurredAt()).isEqualTo(NOW);
    assertThat(message.zones()).hasSize(2);
    assertThat(message.zones().get(0).zoneId()).isEqualTo(VIP_ZONE);
    assertThat(message.zones().get(0).price().amount()).hasToString("150.00");
    assertThat(message.zones().get(0).capacity()).isEqualTo(20);
    assertThat(parsed.type()).isEqualTo("EventPublished");
  }

  @Test
  void should_read_a_rescheduled_event() {
    Instant newStart = EVENT_STARTS.plus(Duration.ofDays(7));

    var parsed =
        (CatalogEventReader.Parsed.Rescheduled)
            read(
                rescheduled(
                    MESSAGE, EVENT_ID, NOW.plusSeconds(5), newStart, newStart.plusSeconds(3600)));

    EventRescheduledMessage message = parsed.message();
    assertThat(message.eventId()).isEqualTo(EVENT_ID);
    assertThat(message.startsAt()).isEqualTo(newStart);
    assertThat(message.occurredAt()).isEqualTo(NOW.plusSeconds(5));
  }

  @Test
  void should_read_a_cancelled_event_with_the_envelope_instant() {
    var parsed =
        (CatalogEventReader.Parsed.Cancelled)
            read(cancelled(MESSAGE, EVENT_ID, NOW.plusSeconds(9), "Venue unavailable"));

    EventCancelledMessage message = parsed.message();
    assertThat(message.eventId()).isEqualTo(EVENT_ID);
    assertThat(message.occurredAt()).isEqualTo(NOW.plusSeconds(9));
    assertThat(message.messageId()).isEqualTo(MESSAGE);
  }

  // ---- tolerance: what it does not need, it does not read ----

  @Test
  void should_ignore_unknown_fields_everywhere() {
    String json =
        mutated(
            published(),
            envelope -> {
              envelope.put("causationId", UUID.randomUUID().toString());
              envelope.put("somethingNew", true);
              data(envelope).put("promoter", "Acme");
              firstZone(envelope).put("section", "A");
              ((ObjectNode) firstZone(envelope).get("price")).put("tax", "18.00");
            });

    assertThat(read(json)).isInstanceOf(CatalogEventReader.Parsed.Published.class);
  }

  @Test
  void should_not_need_the_fields_it_does_not_use() {
    String json =
        mutated(
            published(),
            envelope -> {
              data(envelope).remove("organizerId");
              data(envelope).remove("title");
              data(envelope).remove("venue");
              envelope.remove("correlationId");
              envelope.remove("producer");
            });

    assertThat(read(json)).isInstanceOf(CatalogEventReader.Parsed.Published.class);
  }

  // ---- poison: what can never be processed ----

  static Stream<Arguments> poison() {
    String valid = published();
    return Stream.of(
        Arguments.of("not json", "{ not json".getBytes(StandardCharsets.UTF_8)),
        Arguments.of("empty body", new byte[0]),
        Arguments.of("an array", "[]".getBytes(StandardCharsets.UTF_8)),
        Arguments.of("a number", "42".getBytes(StandardCharsets.UTF_8)),
        Arguments.of("no messageId", mutate(valid, e -> e.remove("messageId"))),
        Arguments.of("messageId not a uuid", mutate(valid, e -> e.put("messageId", "nope"))),
        Arguments.of("no type", mutate(valid, e -> e.remove("type"))),
        Arguments.of("unknown type", mutate(valid, e -> e.put("type", "EventExploded"))),
        Arguments.of("no version", mutate(valid, e -> e.remove("version"))),
        Arguments.of("version as text", mutate(valid, e -> e.put("version", "1"))),
        Arguments.of("unsupported version", mutate(valid, e -> e.put("version", 2))),
        Arguments.of("no occurredAt", mutate(valid, e -> e.remove("occurredAt"))),
        Arguments.of("occurredAt not iso", mutate(valid, e -> e.put("occurredAt", "yesterday"))),
        Arguments.of("no data", mutate(valid, e -> e.remove("data"))),
        Arguments.of("data not an object", mutate(valid, e -> e.put("data", "x"))),
        Arguments.of("no eventId", mutate(valid, e -> data(e).remove("eventId"))),
        Arguments.of("eventId not a uuid", mutate(valid, e -> data(e).put("eventId", "x"))),
        Arguments.of("no startsAt", mutate(valid, e -> data(e).remove("startsAt"))),
        Arguments.of("startsAt not iso", mutate(valid, e -> data(e).put("startsAt", "soon"))),
        Arguments.of("no zones", mutate(valid, e -> data(e).remove("zones"))),
        Arguments.of("empty zones", mutate(valid, e -> data(e).putArray("zones"))),
        Arguments.of("zones not an array", mutate(valid, e -> data(e).put("zones", "x"))),
        Arguments.of("zone without id", mutate(valid, e -> firstZone(e).remove("zoneId"))),
        Arguments.of("zone without name", mutate(valid, e -> firstZone(e).remove("name"))),
        Arguments.of("zone without price", mutate(valid, e -> firstZone(e).remove("price"))),
        Arguments.of(
            "price without amount",
            mutate(valid, e -> ((ObjectNode) firstZone(e).get("price")).remove("amount"))),
        Arguments.of(
            "price amount not a number",
            mutate(valid, e -> ((ObjectNode) firstZone(e).get("price")).put("amount", "abc"))),
        Arguments.of(
            "price currency invalid",
            mutate(valid, e -> ((ObjectNode) firstZone(e).get("price")).put("currency", "pen"))),
        Arguments.of("zone without capacity", mutate(valid, e -> firstZone(e).remove("capacity"))),
        Arguments.of("zone capacity zero", mutate(valid, e -> firstZone(e).put("capacity", 0))),
        Arguments.of("zone capacity text", mutate(valid, e -> firstZone(e).put("capacity", "20"))),
        Arguments.of(
            "zone capacity decimal", mutate(valid, e -> firstZone(e).put("capacity", 2.5))),
        Arguments.of(
            "cancelled without eventId",
            bytes(mutated(cancelled(MESSAGE, EVENT_ID, NOW, "x"), e -> data(e).remove("eventId")))),
        Arguments.of(
            "rescheduled without endsAt",
            bytes(
                mutated(
                    rescheduled(MESSAGE, EVENT_ID, NOW, EVENT_STARTS, ENDS),
                    e -> data(e).remove("endsAt")))));
  }

  private static byte[] mutate(String json, Consumer<ObjectNode> change) {
    return bytes(mutated(json, change));
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @ParameterizedTest(name = "{0} is poison")
  @MethodSource("poison")
  void should_fail_as_poison_when_the_message_cannot_ever_be_processed(String name, byte[] body) {
    assertThatThrownBy(() -> reader.read(body)).isInstanceOf(PoisonMessageException.class);
  }

  @Test
  void should_name_the_missing_field_in_the_failure() {
    String json = mutated(published(), e -> data(e).remove("eventId"));

    assertThatThrownBy(() -> read(json))
        .isInstanceOf(PoisonMessageException.class)
        .hasMessageContaining("eventId");
  }

  @Test
  void should_name_the_version_when_it_is_not_supported() {
    String json = mutated(published(), e -> e.put("version", 3));

    assertThatThrownBy(() -> read(json))
        .isInstanceOf(PoisonMessageException.class)
        .hasMessageContaining("version 3");
  }
}
