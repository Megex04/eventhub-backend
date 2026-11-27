package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.DomainEvent;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.contracttesting.EventSchemas;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every payload the service produces is validated against its JSON Schema in {@code
 * contracts/events}, built from the events a real booking records. The negative cases prove the
 * validator does not accept just anything.
 */
class BookingEventSchemaContractTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final OutboxEnvelopeFactory FACTORY = new OutboxEnvelopeFactory(JSON);
  private static final UUID MESSAGE = uuid(8001);
  private static final UUID CORRELATION = uuid(8002);

  /** The five events, from the transitions that really produce them. */
  private static List<DomainEvent> allFiveEvents() {
    List<DomainEvent> events = new ArrayList<>();

    Booking confirmedThenRefunded =
        newBooking(uuid(1), USER, at(NOW), new RequestedItem(VIP_ZONE, 2));
    confirmedThenRefunded.beginPayment(uuid(7), "tok_approved", at(NOW));
    confirmedThenRefunded.capturePayment(at(NOW.plusSeconds(1)));
    confirmedThenRefunded.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(2)));
    confirmedThenRefunded.completeRefund(at(NOW.plusSeconds(3)));
    events.addAll(confirmedThenRefunded.pullDomainEvents());

    Booking expired =
        newBooking(
            uuid(2),
            USER,
            at(NOW),
            new RequestedItem(VIP_ZONE, 1),
            new RequestedItem(GENERAL_ZONE, 3));
    expired.expire(at(EXPIRES_AT));
    events.addAll(expired.pullDomainEvents());
    return events;
  }

  private static OutboxEnvelopeFactory.Row rowOf(DomainEvent event) {
    return FACTORY.create(event, MESSAGE, CORRELATION);
  }

  static Stream<Arguments> events() {
    return allFiveEvents().stream()
        .map(event -> Arguments.of(event.getClass().getSimpleName(), event));
  }

  private static String mutated(String eventType, Consumer<ObjectNode> mutation) {
    DomainEvent event =
        allFiveEvents().stream()
            .filter(candidate -> candidate.getClass().getSimpleName().equals(eventType))
            .findFirst()
            .orElseThrow();
    ObjectNode envelope = (ObjectNode) JSON.readTree(rowOf(event).payload());
    mutation.accept(envelope);
    return JSON.writeValueAsString(envelope);
  }

  // ---- positive ----

  @Test
  void should_cover_the_five_events_of_the_contract_when_a_booking_goes_through_its_life() {
    assertThat(allFiveEvents())
        .extracting(event -> event.getClass().getSimpleName())
        .containsExactlyInAnyOrder(
            "BookingCreated",
            "BookingConfirmed",
            "BookingCancelled",
            "BookingPaymentRefunded",
            "BookingCreated",
            "BookingExpired");
  }

  @ParameterizedTest(name = "{0} is valid")
  @MethodSource("events")
  void should_validate_against_its_schema_when_the_event_is_serialized(
      String eventType, DomainEvent event) {
    OutboxEnvelopeFactory.Row row = rowOf(event);

    assertThat(row.eventType()).isEqualTo(eventType);
    assertThat(EventSchemas.violations(eventType, row.payload())).isEmpty();
  }

  @ParameterizedTest(name = "{0} carries the envelope fields")
  @MethodSource("events")
  void should_carry_the_envelope_v1_when_the_event_is_serialized(
      String eventType, DomainEvent event) {
    ObjectNode envelope = (ObjectNode) JSON.readTree(rowOf(event).payload());

    assertThat(envelope.get("messageId").asString()).isEqualTo(MESSAGE.toString());
    assertThat(envelope.get("correlationId").asString()).isEqualTo(CORRELATION.toString());
    assertThat(envelope.get("producer").asString()).isEqualTo("booking-service");
    assertThat(envelope.get("version").asInt()).isEqualTo(1);
    assertThat(envelope.get("type").asString()).isEqualTo(eventType);
  }

  @Test
  void should_write_money_as_a_string_with_two_decimals_when_the_event_carries_an_amount() {
    DomainEvent created =
        allFiveEvents().stream()
            .filter(e -> e.getClass().getSimpleName().equals("BookingCreated"))
            .findFirst()
            .orElseThrow();

    ObjectNode envelope = (ObjectNode) JSON.readTree(rowOf(created).payload());

    assertThat(envelope.get("data").get("total").get("amount").asString()).isEqualTo("300.00");
    assertThat(envelope.get("data").get("items").get(0).get("unitPrice").get("amount").asString())
        .isEqualTo("150.00");
  }

  // ---- negative: the validator rejects what the contract forbids ----

  @Test
  void should_reject_a_confirmation_without_its_payment_id() {
    String payload =
        mutated("BookingConfirmed", e -> ((ObjectNode) e.get("data")).remove("paymentId"));

    assertThat(EventSchemas.violations("BookingConfirmed", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_refund_without_its_reason() {
    String payload =
        mutated("BookingPaymentRefunded", e -> ((ObjectNode) e.get("data")).remove("reason"));

    assertThat(EventSchemas.violations("BookingPaymentRefunded", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_refund_with_a_reason_outside_the_contract() {
    String payload =
        mutated(
            "BookingPaymentRefunded", e -> ((ObjectNode) e.get("data")).put("reason", "BECAUSE"));

    assertThat(EventSchemas.violations("BookingPaymentRefunded", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_cancellation_with_a_reason_of_another_event() {
    String payload =
        mutated(
            "BookingCancelled",
            e -> ((ObjectNode) e.get("data")).put("reason", "BOOKING_EXPIRED_DURING_PAYMENT"));

    assertThat(EventSchemas.violations("BookingCancelled", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_payload_from_another_producer() {
    String payload = mutated("BookingExpired", e -> e.put("producer", "catalog-service"));

    assertThat(EventSchemas.violations("BookingExpired", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_payload_of_an_unknown_version() {
    String payload = mutated("BookingExpired", e -> e.put("version", 2));

    assertThat(EventSchemas.violations("BookingExpired", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_payload_whose_type_is_another_events() {
    String payload = mutated("BookingExpired", e -> e.put("type", "BookingCreated"));

    assertThat(EventSchemas.violations("BookingExpired", payload)).isNotEmpty();
  }

  @Test
  void should_reject_an_amount_that_is_not_a_two_decimal_string() {
    String payload =
        mutated(
            "BookingConfirmed",
            e -> ((ObjectNode) ((ObjectNode) e.get("data")).get("total")).put("amount", "300"));

    assertThat(EventSchemas.violations("BookingConfirmed", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_booking_created_without_items() {
    String payload = mutated("BookingCreated", e -> ((ObjectNode) e.get("data")).putArray("items"));

    assertThat(EventSchemas.violations("BookingCreated", payload)).isNotEmpty();
  }

  @Test
  void should_reject_a_property_the_contract_does_not_know() {
    String payload =
        mutated("BookingExpired", e -> ((ObjectNode) e.get("data")).put("surprise", 1));

    assertThat(EventSchemas.violations("BookingExpired", payload)).isNotEmpty();
  }

  @Test
  void should_reject_identifiers_that_are_not_uuids() {
    assertThat(
            EventSchemas.violations(
                "BookingExpired", mutated("BookingExpired", e -> e.put("messageId", "not-a-uuid"))))
        .isNotEmpty();
    assertThat(
            EventSchemas.violations(
                "BookingExpired",
                mutated(
                    "BookingExpired", e -> ((ObjectNode) e.get("data")).put("userId", "alice"))))
        .isNotEmpty();
  }

  @Test
  void should_reject_a_timestamp_that_is_not_iso_8601() {
    String payload =
        mutated("BookingExpired", e -> ((ObjectNode) e.get("data")).put("expiredAt", "yesterday"));

    assertThat(EventSchemas.violations("BookingExpired", payload)).isNotEmpty();
  }
}
