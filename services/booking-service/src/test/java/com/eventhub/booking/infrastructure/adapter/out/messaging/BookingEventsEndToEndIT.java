package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.contracttesting.EventSchemas;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

/** From the HTTP API to RabbitMQ: what a consumer would see for a booking, in order. */
class BookingEventsEndToEndIT extends MessagingIT {

  @Test
  void should_deliver_created_confirmed_cancelled_and_payment_refunded_in_that_order()
      throws Exception {
    UUID correlation = UUID.randomUUID();
    UUID id =
        idOf(
            createWithCorrelation(alice, UUID.randomUUID(), vipBody(2), correlation)
                .andExpect(status().isCreated())
                .andReturn());
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    cancel(alice, id).andExpect(status().isOk());

    assertThat(drain()).isEqualTo(4);

    List<Message> messages = receive(4);
    assertThat(keysOf(messages))
        .containsExactly(
            "booking.booking.created.v1",
            "booking.booking.confirmed.v1",
            "booking.booking.cancelled.v1",
            "booking.booking.payment-refunded.v1");
    for (Message message : messages) {
      assertThat(EventSchemas.violations(field(message, "$.type"), body(message))).isEmpty();
      assertThat(field(message, "$.data.bookingId")).isEqualTo(id.toString());
    }
    assertThat(field(messages.get(0), "$.correlationId")).isEqualTo(correlation.toString());
    assertThat(field(messages.get(1), "$.data.paymentId"))
        .as("the confirmation and the refund speak of the same payment")
        .isEqualTo(field(messages.get(3), "$.data.paymentId"));
    assertThat(field(messages.get(2), "$.data.reason")).isEqualTo("USER_REQUEST");
    assertThat(field(messages.get(3), "$.data.reason")).isEqualTo("BOOKING_CANCELLED");
    assertThat(field(messages.get(3), "$.data.amount.amount")).isEqualTo("300.00");
    assertThat(
            messages.stream().map(m -> m.getMessageProperties().getMessageId()).distinct().toList())
        .hasSize(4);
  }

  @Test
  void should_use_the_row_ids_as_message_ids_and_keep_them_in_creation_order() throws Exception {
    UUID id = booked(alice, 1);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    cancel(alice, id).andExpect(status().isOk());
    List<String> rows =
        app.jdbc.queryForList(
            "SELECT id::text FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, id",
            String.class,
            id);

    drain();

    assertThat(receive(rows.size()).stream().map(m -> field(m, "$.messageId")).toList())
        .containsExactlyElementsOf(rows);
  }

  @Test
  void should_deliver_created_then_expired_when_the_hold_ends() throws Exception {
    UUID id = booked(alice, 2);
    clock.advance(HOLD);
    assertThat(expire.expireDue(10)).isEqualTo(1);

    drain();

    List<Message> messages = receive(2);
    assertThat(keysOf(messages))
        .containsExactly("booking.booking.created.v1", "booking.booking.expired.v1");
    assertThat(field(messages.get(1), "$.data.bookingId")).isEqualTo(id.toString());
    assertThat(EventSchemas.violations("BookingExpired", body(messages.get(1)))).isEmpty();
  }

  @Test
  void should_deliver_a_refund_that_was_pending_when_the_reconciler_completes_it()
      throws Exception {
    UUID id = booked(alice, 1);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    gateway.failAllRefunds(true);
    cancel(alice, id).andExpect(status().isOk());
    drain();
    receive(3);
    assertThat(queueIsEmpty()).as("no refund event while the refund is pending").isTrue();

    gateway.failAllRefunds(false);
    reconcile.reconcile();
    drain();

    Message refund = receive();
    assertThat(routingKey(refund)).isEqualTo("booking.booking.payment-refunded.v1");
    assertThat(EventSchemas.violations("BookingPaymentRefunded", body(refund))).isEmpty();
  }
}
