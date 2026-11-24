package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventhub.catalog.support.SharedRabbit;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.testcontainers.DockerClientFactory;

/**
 * The broker really stops answering (its container is paused, so connections stay open but nothing
 * is confirmed). The outbox must keep the row and, once the broker is back, send that same message.
 */
class BrokerOutageIT extends MessagingIntegrationTest {

  private static void pauseBroker() {
    DockerClientFactory.instance().client().pauseContainerCmd(SharedRabbit.containerId()).exec();
  }

  private static void resumeBroker() {
    DockerClientFactory.instance().client().unpauseContainerCmd(SharedRabbit.containerId()).exec();
  }

  @Test
  void should_keep_the_row_and_resend_the_same_message_when_the_broker_comes_back()
      throws Exception {
    UUID eventId = createDraftWithZone(organizer());
    publishViaApi(eventId);
    UUID rowId = onlyOutboxRowOf(eventId);
    String storedPayload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE id = ?", String.class, rowId);

    pauseBroker();
    try {
      assertThat(relay.publishPending())
          .as("nothing is confirmed while the broker is down")
          .isZero();
    } finally {
      resumeBroker();
    }

    Map<String, Object> failed =
        jdbc.queryForMap(
            "SELECT published_at, attempts, last_error FROM outbox_event WHERE id = ?", rowId);
    assertThat(failed.get("published_at")).as("the row is kept, not lost").isNull();
    assertThat(failed.get("attempts")).isEqualTo(1);
    assertThat((String) failed.get("last_error")).isNotBlank();

    clock.advance(Duration.ofMinutes(1));
    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofSeconds(2))
        .until(() -> relay.publishPending() == 1);

    Message delivered = receive();
    assertThat(field(delivered, "$.messageId")).isEqualTo(rowId.toString());
    assertThat((Object) JsonPath.read(body(delivered), "$"))
        .as("the payload is the one that was stored, not a rebuilt one")
        .isEqualTo(JsonPath.read(storedPayload, "$"));
    assertThat(
            jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?",
                Boolean.class,
                rowId))
        .isTrue();
  }

  @Test
  void should_not_confirm_anything_to_the_caller_of_the_api_when_the_broker_is_down()
      throws Exception {
    pauseBroker();
    try {
      UUID eventId = createDraftWithZone(organizer());
      publishViaApi(eventId);
      assertThat(
              jdbc.queryForObject("SELECT status FROM event WHERE id = ?", String.class, eventId))
          .as("publishing does not wait for the broker: the outbox decouples them")
          .isEqualTo("PUBLISHED");
    } finally {
      resumeBroker();
    }
  }
}
