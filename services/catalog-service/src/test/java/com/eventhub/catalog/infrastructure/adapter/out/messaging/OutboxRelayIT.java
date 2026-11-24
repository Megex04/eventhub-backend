package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.catalog.support.EventSchemas;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;

class OutboxRelayIT extends MessagingIntegrationTest {

  private Map<String, Object> row(UUID rowId) {
    return jdbc.queryForMap(
        "SELECT published_at, attempts, last_error, next_attempt_at FROM outbox_event WHERE id = ?",
        rowId);
  }

  private static List<String> keysOf(List<Message> messages) {
    return messages.stream().map(MessagingIntegrationTest::routingKey).toList();
  }

  // ---- API -> RabbitMQ ----

  @Test
  void should_deliver_a_schema_valid_envelope_when_an_event_is_published_through_the_api()
      throws Exception {
    UUID correlation = UUID.randomUUID();
    UUID eventId = createDraftWithZone(organizer());
    publishViaApi(eventId, correlation);
    UUID rowId = onlyOutboxRowOf(eventId);

    assertThat(relay.publishPending()).isEqualTo(1);

    Message message = receive();
    assertThat(routingKey(message)).isEqualTo("catalog.event.published.v1");
    assertThat(EventSchemas.violations("EventPublished", body(message))).isEmpty();
    assertThat(field(message, "$.messageId")).isEqualTo(rowId.toString());
    assertThat(field(message, "$.correlationId")).isEqualTo(correlation.toString());
    assertThat(field(message, "$.producer")).isEqualTo("catalog-service");
    assertThat(field(message, "$.data.eventId")).isEqualTo(eventId.toString());
    assertThat(message.getMessageProperties().getMessageId()).isEqualTo(rowId.toString());
    assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(correlation.toString());
    assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
    assertThat(message.getMessageProperties().getReceivedDeliveryMode())
        .isEqualTo(MessageDeliveryMode.PERSISTENT);
  }

  @Test
  void should_mark_the_row_published_without_failures_when_the_broker_confirms() throws Exception {
    UUID eventId = createDraftWithZone(organizer());
    publishViaApi(eventId);
    UUID rowId = onlyOutboxRowOf(eventId);

    relay.publishPending();

    Map<String, Object> row = row(rowId);
    assertThat(row.get("published_at")).isNotNull();
    assertThat(row.get("attempts")).isEqualTo(0);
    assertThat(row.get("last_error")).isNull();
  }

  @Test
  void should_use_the_generated_id_for_the_event_when_the_request_sends_no_correlation_id()
      throws Exception {
    UUID eventId = createDraftWithZone(organizer());

    String echoed =
        mockMvc
            .perform(withToken(post(EVENTS + "/" + eventId + "/publish"), organizer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Correlation-Id");
    relay.publishPending();

    assertThat(UUID.fromString(echoed)).isNotNull();
    assertThat(field(receive(), "$.correlationId")).isEqualTo(echoed);
  }

  @Test
  void should_echo_the_correlation_id_when_the_caller_sends_one() throws Exception {
    UUID sent = UUID.randomUUID();

    mockMvc
        .perform(get(EVENTS).header("X-Correlation-Id", sent.toString()))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("X-Correlation-Id", sent.toString()));
  }

  @Test
  void should_use_the_contract_routing_key_and_schema_when_an_event_is_rescheduled_or_cancelled()
      throws Exception {
    UUID eventId = createPublished(organizer());
    drain();
    receive();

    rescheduleViaApi(eventId);
    drain();
    Message rescheduled = receive();
    cancelViaApi(eventId);
    drain();
    Message cancelled = receive();

    assertThat(routingKey(rescheduled)).isEqualTo("catalog.event.rescheduled.v1");
    assertThat(EventSchemas.violations("EventRescheduled", body(rescheduled))).isEmpty();
    assertThat(routingKey(cancelled)).isEqualTo("catalog.event.cancelled.v1");
    assertThat(EventSchemas.violations("EventCancelled", body(cancelled))).isEmpty();
  }

  // ---- ordering ----

  @Test
  void should_send_one_event_per_aggregate_per_pass_and_in_order() throws Exception {
    UUID eventId = createPublished(organizer());
    rescheduleViaApi(eventId);

    assertThat(relay.publishPending())
        .as("only the publish; the reschedule waits behind it")
        .isEqualTo(1);
    assertThat(relay.publishPending()).isEqualTo(1);
    assertThat(relay.publishPending()).isZero();

    assertThat(keysOf(receive(2)))
        .containsExactly("catalog.event.published.v1", "catalog.event.rescheduled.v1");
  }

  @Test
  void should_not_let_a_reschedule_overtake_a_publish_that_is_backing_off() throws Exception {
    UUID eventId = createPublished(organizer());
    rescheduleViaApi(eventId);
    UUID publishRow =
        jdbc.queryForObject(
            "SELECT id FROM outbox_event WHERE aggregate_id = ? AND event_type = 'EventPublished'",
            UUID.class,
            eventId);

    broker.failAlways();
    assertThat(relay.publishPending()).isZero();

    Map<String, Object> failed = row(publishRow);
    assertThat(failed.get("published_at")).isNull();
    assertThat(failed.get("attempts")).isEqualTo(1);
    assertThat((String) failed.get("last_error")).contains("Simulated broker failure");
    assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant())
        .as("backing off")
        .isAfter(clock.instant());
    assertThat(queueIsEmpty()).as("the reschedule must not be sent in its place").isTrue();

    broker.reset();
    assertThat(relay.publishPending()).as("still inside the backoff").isZero();
    clock.advance(Duration.ofSeconds(2));
    assertThat(drain()).isEqualTo(2);
    assertThat(keysOf(receive(2)))
        .containsExactly("catalog.event.published.v1", "catalog.event.rescheduled.v1");
  }

  @Test
  void should_cut_the_batch_at_the_first_failure_and_keep_creation_order() throws Exception {
    UUID first = createPublished(organizer());
    UUID second = createPublished(organizer());
    UUID third = createPublished(organizer());
    UUID secondRow = onlyOutboxRowOf(second);
    broker.failWhen(message -> message.id().equals(secondRow));

    assertThat(relay.publishPending())
        .as("the first is sent, the second fails, the third is left")
        .isEqualTo(1);

    assertThat(row(onlyOutboxRowOf(first)).get("published_at")).isNotNull();
    assertThat(row(secondRow).get("attempts")).isEqualTo(1);
    Map<String, Object> untouched = row(onlyOutboxRowOf(third));
    assertThat(untouched.get("published_at")).isNull();
    assertThat(untouched.get("attempts")).as("never tried").isEqualTo(0);

    broker.reset();
    clock.advance(Duration.ofSeconds(2));
    assertThat(drain()).isEqualTo(2);
    assertThat(receive(3).stream().map(m -> field(m, "$.data.eventId")).toList())
        .containsExactly(first.toString(), second.toString(), third.toString());
  }

  // ---- parking ----

  @Test
  void should_park_a_row_and_hold_its_aggregate_back_when_its_attempts_run_out() throws Exception {
    UUID eventId = createPublished(organizer());
    rescheduleViaApi(eventId);
    UUID publishRow =
        jdbc.queryForObject(
            "SELECT id FROM outbox_event WHERE aggregate_id = ? AND event_type = 'EventPublished'",
            UUID.class,
            eventId);
    double parkedBefore = count("outbox.parked.total");

    broker.failAlways();
    for (int attempt = 0; attempt < 5; attempt++) {
      relay.publishPending();
      clock.advance(Duration.ofMinutes(1));
    }

    assertThat(row(publishRow).get("attempts")).isEqualTo(5);
    assertThat(gauge("outbox.parked")).isEqualTo(1);
    assertThat(gauge("outbox.pending")).as("the reschedule still waits").isEqualTo(1);
    assertThat(count("outbox.parked.total") - parkedBefore).isEqualTo(1);

    broker.reset();
    assertThat(drain()).as("a parked row is not retried and keeps its aggregate blocked").isZero();
    assertThat(queueIsEmpty()).isTrue();

    jdbc.update(
        "UPDATE outbox_event SET attempts = 0, next_attempt_at = created_at WHERE id = ?",
        publishRow);
    assertThat(drain()).isEqualTo(2);
    assertThat(keysOf(receive(2)))
        .containsExactly("catalog.event.published.v1", "catalog.event.rescheduled.v1");
  }

  // ---- metrics ----

  @Test
  void should_report_the_backlog_and_its_age_until_the_relay_catches_up() throws Exception {
    createPublished(organizer());
    createPublished(organizer());
    clock.advance(Duration.ofSeconds(30));
    double publishedBefore = count("outbox.published");

    assertThat(gauge("outbox.pending")).isEqualTo(2);
    assertThat(gauge("outbox.oldest.age")).isGreaterThanOrEqualTo(30);

    drain();

    assertThat(gauge("outbox.pending")).isZero();
    assertThat(gauge("outbox.oldest.age")).isZero();
    assertThat(count("outbox.published") - publishedBefore).isEqualTo(2);
  }

  @Test
  void should_count_failed_attempts_when_the_broker_does_not_confirm() throws Exception {
    createPublished(organizer());
    double failuresBefore = count("outbox.failures");

    broker.failAlways();
    relay.publishPending();

    assertThat(count("outbox.failures") - failuresBefore).isEqualTo(1);
  }
}
