package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.support.SharedRabbit;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.testcontainers.DockerClientFactory;

/**
 * The broker really stops answering (its container is paused, so connections stay open but nothing
 * is confirmed). Booking keeps working, because the outbox decouples it from the broker, and once
 * the broker is back the rows are sent with the very ids and payloads that were stored.
 */
class BrokerOutageIT extends MessagingIT {

  private static void pauseBroker() {
    DockerClientFactory.instance().client().pauseContainerCmd(SharedRabbit.containerId()).exec();
  }

  private static void resumeBroker() {
    DockerClientFactory.instance().client().unpauseContainerCmd(SharedRabbit.containerId()).exec();
  }

  @Test
  void should_create_and_pay_normally_while_the_broker_is_down() throws Exception {
    pauseBroker();
    UUID id;
    try {
      id = booked(alice, 2);
      pay(alice, id, "tok_approved")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CONFIRMED"));
      fetch(alice, id).andExpect(jsonPath("$.status").value("CONFIRMED"));
    } finally {
      resumeBroker();
    }

    assertThat(app.count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL"))
        .as("both events are waiting in the outbox")
        .isEqualTo(2);
  }

  @Test
  void should_keep_the_rows_and_resend_the_same_messages_when_the_broker_comes_back()
      throws Exception {
    UUID id = booked(alice, 2);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    UUID creationRow = outboxRowOf(id, "BookingCreated");
    UUID confirmationRow = outboxRowOf(id, "BookingConfirmed");
    String storedCreation =
        app.jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE id = ?", String.class, creationRow);

    pauseBroker();
    try {
      assertThat(relay.publishPending())
          .as("nothing is confirmed while the broker is down")
          .isZero();
    } finally {
      resumeBroker();
    }

    Map<String, Object> failed = row(creationRow);
    assertThat(failed.get("published_at")).as("the row is kept, not lost").isNull();
    assertThat(failed.get("attempts")).isEqualTo(1);
    assertThat((String) failed.get("last_error")).isNotBlank();

    clock.advance(Duration.ofMinutes(1));
    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofSeconds(2))
        .until(() -> relay.publishPending() == 1);
    drain();

    // Delivery is at-least-once: the attempt made while the broker was paused may well have reached
    // it once it woke up, so the creation can arrive twice. Both copies carry the same message id
    // and payload (that is what consumers deduplicate on), and the confirmation never overtakes it.
    List<Message> delivered = receiveAll();
    List<String> ids = delivered.stream().map(m -> field(m, "$.messageId")).toList();
    assertThat(ids).containsOnly(creationRow.toString(), confirmationRow.toString());
    assertThat(ids).contains(creationRow.toString(), confirmationRow.toString());
    assertThat(ids.lastIndexOf(confirmationRow.toString()))
        .as("the confirmation comes after every copy of the creation")
        .isGreaterThan(ids.lastIndexOf(creationRow.toString()));
    for (Message copy : delivered) {
      if (field(copy, "$.messageId").equals(creationRow.toString())) {
        assertThat((Object) JsonPath.read(body(copy), "$"))
            .as("the payload is the one that was stored, not a rebuilt one")
            .isEqualTo(JsonPath.read(storedCreation, "$"));
      }
    }
    assertThat(app.count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")).isZero();
  }
}
