package com.eventhub.booking.infrastructure.adapter.in.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static com.eventhub.booking.support.CatalogPayloads.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.support.CatalogPayloads.Zone;
import com.eventhub.contracttesting.EventSchemas;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What this service consumes is checked against the catalog schemas: the messages the tests send
 * are valid catalog messages (including the shape of a re-emission), and the reader accepts them.
 * Were the tests to send something catalog never would, they would prove nothing about production.
 */
class CatalogPayloadsContractTest {

  private static final UUID EVENT_ID = uuid(9100);
  private static final List<Zone> ZONES =
      List.of(
          new Zone(VIP_ZONE, "VIP", 20, "150.00"), new Zone(GENERAL_ZONE, "General", 100, "50.00"));

  private final CatalogEventReader reader = new CatalogEventReader(JSON);

  private void assertValidAndReadable(String type, String json) {
    assertThat(EventSchemas.violations(type, json)).as("%s against its schema", type).isEmpty();
    assertThat(reader.read(json.getBytes(StandardCharsets.UTF_8)).type()).isEqualTo(type);
  }

  @Test
  void should_be_valid_and_readable_when_catalog_publishes_an_event() {
    assertValidAndReadable(
        "EventPublished",
        published(
            uuid(1), EVENT_ID, NOW, EVENT_STARTS, EVENT_STARTS.plus(Duration.ofHours(3)), ZONES));
  }

  @Test
  void should_be_valid_and_readable_when_catalog_reschedules_an_event() {
    assertValidAndReadable(
        "EventRescheduled",
        rescheduled(
            uuid(2),
            EVENT_ID,
            NOW.plusSeconds(60),
            EVENT_STARTS.plus(Duration.ofDays(7)),
            EVENT_STARTS.plus(Duration.ofDays(7)).plus(Duration.ofHours(3))));
  }

  @Test
  void should_be_valid_and_readable_when_catalog_cancels_an_event() {
    assertValidAndReadable(
        "EventCancelled", cancelled(uuid(3), EVENT_ID, NOW.plusSeconds(120), "Venue unavailable"));
  }

  @Test
  void should_be_valid_and_readable_when_catalog_replays_a_cancelled_event_with_one_instant() {
    var at = NOW.plusSeconds(300);

    assertValidAndReadable(
        "EventPublished",
        published(
            uuid(4), EVENT_ID, at, EVENT_STARTS, EVENT_STARTS.plus(Duration.ofHours(3)), ZONES));
    assertValidAndReadable("EventCancelled", cancelled(uuid(5), EVENT_ID, at, "Venue unavailable"));
  }
}
