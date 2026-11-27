package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.contracttesting.EventSchemas;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

class OutboxRelayIT extends MessagingIT {

  private UUID bookedWithCorrelation(UUID correlation) throws Exception {
    return idOf(
        createWithCorrelation(alice, UUID.randomUUID(), vipBody(2), correlation)
            .andExpect(status().isCreated())
            .andReturn());
  }

  // ---- what is sent ----

  @Test
  void should_deliver_a_schema_valid_envelope_when_a_booking_is_created_through_the_api()
      throws Exception {
    UUID correlation = UUID.randomUUID();
    UUID bookingId = bookedWithCorrelation(correlation);
    UUID rowId = onlyOutboxRowOf(bookingId);

    assertThat(relay.publishPending()).isEqualTo(1);

    Message message = receive();
    assertThat(routingKey(message)).isEqualTo("booking.booking.created.v1");
    assertThat(EventSchemas.violations("BookingCreated", body(message))).isEmpty();
    assertThat(field(message, "$.producer")).isEqualTo("booking-service");
    assertThat(field(message, "$.data.bookingId")).isEqualTo(bookingId.toString());
    assertThat(field(message, "$.data.userId")).isEqualTo(USER.toString());
    assertThat(field(message, "$.data.total.amount")).isEqualTo("300.00");
    assertThat(field(message, "$.messageId"))
        .as("the message id is the id of the outbox row")
        .isEqualTo(rowId.toString());
    assertThat(message.getMessageProperties().getMessageId()).isEqualTo(rowId.toString());
  }

  @Test
  void should_mark_the_row_published_without_failures_when_the_broker_confirms() throws Exception {
    UUID bookingId = booked(alice, 1);
    UUID rowId = onlyOutboxRowOf(bookingId);

    drain();

    Map<String, Object> sent = row(rowId);
    assertThat(sent.get("published_at")).isNotNull();
    assertThat(sent.get("attempts")).isEqualTo(0);
    assertThat(sent.get("last_error")).isNull();
  }

  @Test
  void should_echo_the_correlation_id_when_the_caller_sends_one() throws Exception {
    UUID correlation = UUID.randomUUID();
    bookedWithCorrelation(correlation);

    drain();

    assertThat(field(receive(), "$.correlationId")).isEqualTo(correlation.toString());
  }

  @Test
  void should_use_a_generated_correlation_id_when_the_request_sends_none() throws Exception {
    booked(alice, 1);

    drain();

    String correlation = field(receive(), "$.correlationId");
    assertThat(UUID.fromString(correlation)).isNotNull();
  }

  @Test
  void should_use_the_contract_routing_key_and_schema_for_every_event_of_a_booking_life()
      throws Exception {
    UUID id = booked(alice, 2);
    tick();
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    tick();
    cancel(alice, id).andExpect(status().isOk());
    booked(bob, 1);
    clock.advance(HOLD.plusSeconds(10));
    expire.expireDue(10);

    drain();

    var messages = receive(6);
    for (Message message : messages) {
      assertThat(EventSchemas.violations(field(message, "$.type"), body(message)))
          .as("%s is valid", routingKey(message))
          .isEmpty();
    }
    assertThat(keysOf(messages))
        .containsExactlyInAnyOrder(
            "booking.booking.created.v1",
            "booking.booking.confirmed.v1",
            "booking.booking.cancelled.v1",
            "booking.booking.payment-refunded.v1",
            "booking.booking.created.v1",
            "booking.booking.expired.v1");
  }

  // ---- ordering ----

  @Test
  void should_send_one_event_per_booking_per_pass_and_in_order_when_several_are_pending()
      throws Exception {
    UUID id = booked(alice, 1);
    tick();
    pay(alice, id, "tok_approved").andExpect(status().isOk());

    assertThat(relay.publishPending())
        .as("only the creation; the confirmation waits behind it")
        .isEqualTo(1);
    assertThat(relay.publishPending()).isEqualTo(1);
    assertThat(relay.publishPending()).isZero();

    assertThat(keysOf(receive(2)))
        .containsExactly("booking.booking.created.v1", "booking.booking.confirmed.v1");
  }

  @Test
  void should_not_let_a_confirmation_overtake_the_creation_when_the_creation_is_backing_off()
      throws Exception {
    UUID id = booked(alice, 1);
    tick();
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    UUID creationRow = outboxRowOf(id, "BookingCreated");

    broker.failAlways();
    assertThat(relay.publishPending()).isZero();

    Map<String, Object> failed = row(creationRow);
    assertThat(failed.get("published_at")).isNull();
    assertThat(failed.get("attempts")).isEqualTo(1);
    assertThat((String) failed.get("last_error")).contains("Simulated broker failure");
    assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant())
        .as("backing off")
        .isAfter(clock.instant());
    assertThat(queueIsEmpty()).as("the confirmation must not be sent in its place").isTrue();

    broker.reset();
    assertThat(relay.publishPending()).as("still inside the backoff").isZero();
    clock.advance(Duration.ofSeconds(2));
    assertThat(drain()).isEqualTo(2);
    assertThat(keysOf(receive(2)))
        .containsExactly("booking.booking.created.v1", "booking.booking.confirmed.v1");
  }

  @Test
  void should_cut_the_batch_and_keep_creation_order_when_a_row_fails() throws Exception {
    UUID first = booked(alice, 1);
    tick();
    UUID second = booked(alice, 1);
    tick();
    UUID third = booked(alice, 1);
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
    assertThat(receive(3).stream().map(m -> field(m, "$.data.bookingId")).toList())
        .containsExactly(first.toString(), second.toString(), third.toString());
  }

  // ---- parking ----

  @Test
  void should_park_a_row_and_hold_its_booking_back_when_its_attempts_run_out() throws Exception {
    UUID id = booked(alice, 1);
    tick();
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    UUID creationRow = outboxRowOf(id, "BookingCreated");
    double parkedBefore = count("outbox.parkings");

    broker.failAlways();
    for (int attempt = 0; attempt < 5; attempt++) {
      relay.publishPending();
      clock.advance(Duration.ofMinutes(1));
    }

    assertThat(row(creationRow).get("attempts")).isEqualTo(5);
    assertThat(gauge("outbox.parked")).isEqualTo(1);
    assertThat(gauge("outbox.pending")).as("the confirmation still waits").isEqualTo(1);
    assertThat(count("outbox.parkings") - parkedBefore).isEqualTo(1);

    broker.reset();
    assertThat(drain()).as("a parked row is not retried and keeps its booking blocked").isZero();
    assertThat(queueIsEmpty()).isTrue();

    app.jdbc.update(
        "UPDATE outbox_event SET attempts = 0, next_attempt_at = created_at WHERE id = ?",
        creationRow);
    assertThat(drain()).isEqualTo(2);
    assertThat(keysOf(receive(2)))
        .containsExactly("booking.booking.created.v1", "booking.booking.confirmed.v1");
  }

  // ---- metrics ----

  @Test
  void should_report_the_backlog_and_its_age_when_rows_are_pending() throws Exception {
    booked(alice, 1);
    tick();
    booked(alice, 1);
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
    booked(alice, 1);
    double failuresBefore = count("outbox.failures");

    broker.failAlways();
    relay.publishPending();

    assertThat(count("outbox.failures") - failuresBefore).isEqualTo(1);
  }

  @Test
  void should_keep_publishing_other_bookings_when_one_is_backing_off_or_parked() throws Exception {
    UUID stuck = booked(alice, 1);
    tick();
    UUID healthy = booked(alice, 1);
    UUID stuckRow = onlyOutboxRowOf(stuck);
    broker.failWhen(message -> message.id().equals(stuckRow));

    assertThat(relay.publishPending()).as("the failing row cuts only this pass").isZero();
    assertThat(relay.publishPending())
        .as("next pass: the failing row is backing off, the other booking goes through")
        .isEqualTo(1);
    assertThat(field(receive(), "$.data.bookingId")).isEqualTo(healthy.toString());

    tick();
    UUID laterHealthy = booked(alice, 1);
    for (int attempt = 0; attempt < 4; attempt++) {
      clock.advance(Duration.ofMinutes(1));
      relay.publishPending();
    }
    assertThat(row(stuckRow).get("attempts")).as("parked").isEqualTo(5);

    assertThat(drain()).as("a parked row no longer stops anyone").isEqualTo(1);
    assertThat(field(receive(), "$.data.bookingId")).isEqualTo(laterHealthy.toString());
    assertThat(gauge("outbox.parked")).isEqualTo(1);
  }
}
