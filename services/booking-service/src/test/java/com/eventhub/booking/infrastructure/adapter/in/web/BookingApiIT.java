package com.eventhub.booking.infrastructure.adapter.in.web;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.application.port.out.FakePaymentGateway.Gate;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.Concurrently;
import com.eventhub.booking.support.Concurrently.Outcome;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

class BookingApiIT extends ApiIT {

  private static final UUID KEY = uuid(500);

  // ---------------------------------------------------------------- happy paths

  @Test
  void should_create_pay_and_confirm_a_booking() throws Exception {
    MvcResult created =
        createVip(alice, KEY, 2)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
            .andExpect(jsonPath("$.total.amount").value("300.00"))
            .andExpect(jsonPath("$.total.currency").value("PEN"))
            .andExpect(jsonPath("$.items[0].zoneName").value("VIP"))
            .andExpect(jsonPath("$.items[0].unitPrice.amount").value("150.00"))
            .andExpect(jsonPath("$.items[0].subtotal.amount").value("300.00"))
            .andExpect(jsonPath("$.payment").doesNotExist())
            .andExpect(jsonPath("$.confirmedAt").doesNotExist())
            .andReturn();
    UUID id = idOf(created);
    assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/bookings/" + id);

    pay(alice, id, "tok_approved")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.payment.status").value("CAPTURED"))
        .andExpect(jsonPath("$.payment.amount.amount").value("300.00"))
        .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

    fetch(alice, id)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.payment.status").value("CAPTURED"));
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_write_timestamps_as_iso_8601_in_utc() throws Exception {
    createVip(alice, KEY, 1)
        .andExpect(jsonPath("$.createdAt").value("2026-12-01T10:00:00Z"))
        .andExpect(jsonPath("$.expiresAt").value("2026-12-01T10:10:00Z"));
  }

  @Test
  void should_echo_the_correlation_id_the_caller_sent() throws Exception {
    UUID correlation = UUID.randomUUID();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BOOKINGS)
                .header("Authorization", "Bearer " + alice)
                .header("X-Correlation-Id", correlation.toString()))
        .andExpect(header().string("X-Correlation-Id", correlation.toString()));
  }

  // ---------------------------------------------------------------- idempotency

  @Test
  void should_answer_200_with_the_same_booking_when_the_request_is_repeated() throws Exception {
    UUID id = idOf(createVip(alice, KEY, 2).andExpect(status().isCreated()).andReturn());

    createVip(alice, KEY, 2)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()));
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_answer_409_when_the_key_is_reused_with_another_body() throws Exception {
    createVip(alice, KEY, 2).andExpect(status().isCreated());

    createVip(alice, KEY, 3)
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM))
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "idempotency-key-reuse"));
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_answer_one_201_and_the_rest_200_with_the_same_id_when_one_key_arrives_at_once()
      throws Exception {
    List<Outcome<MockHttpServletResponse>> outcomes =
        Concurrently.run(8, i -> () -> createVip(alice, KEY, 2).andReturn().getResponse());

    assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
    List<Integer> statuses = outcomes.stream().map(o -> o.value().getStatus()).toList();
    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(7);
    assertThat(
            outcomes.stream()
                .map(o -> com.jayway.jsonpath.JsonPath.<String>read(contentOf(o.value()), "$.id"))
                .distinct())
        .hasSize(1);
    assertThat(app.reserved(VIP_ZONE)).as("held once").isEqualTo(2);
    app.assertInventoryInvariant();
  }

  private static String contentOf(MockHttpServletResponse response) {
    try {
      return response.getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------- time

  @Test
  void should_answer_409_to_pay_once_the_hold_ended_and_then_expire_the_booking() throws Exception {
    UUID id = booked(alice, 2);
    clock.advance(HOLD);

    pay(alice, id, "tok_approved")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "invalid-state"));
    assertThat(gateway.chargeCalls()).as("nothing was charged").isZero();

    assertThat(expire.expireDue(10)).isEqualTo(1);
    fetch(alice, id).andExpect(jsonPath("$.status").value("EXPIRED"));
    pay(alice, id, "tok_approved")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "invalid-state"));
    assertThat(app.reserved(VIP_ZONE)).isZero();
    availability(EVENT).andExpect(jsonPath("$.zones[?(@.name=='VIP')].available").value(20));
  }

  // ---------------------------------------------------------------- cancel and refund

  @Test
  void should_cancel_a_confirmed_booking_and_refund_the_payment() throws Exception {
    UUID id = booked(alice, 2);
    pay(alice, id, "tok_approved").andExpect(status().isOk());

    cancel(alice, id)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelReason").value("USER_REQUEST"))
        .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
        .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

    assertThat(gateway.distinctRefunds()).isEqualTo(1);
    assertThat(app.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_leave_the_refund_pending_when_the_gateway_cannot_refund_now() throws Exception {
    UUID id = booked(alice, 1);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    gateway.failAllRefunds(true);

    cancel(alice, id)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.payment.status").value("REFUND_PENDING"));
  }

  @Test
  void should_refuse_to_cancel_a_confirmed_booking_once_the_event_started() throws Exception {
    UUID id = booked(alice, 1);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    clock.set(EVENT_STARTS);

    cancel(alice, id)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "cancel-after-start"));
  }

  @Test
  void should_answer_409_when_cancelling_twice() throws Exception {
    UUID id = booked(alice, 1);
    cancel(alice, id).andExpect(status().isOk());

    cancel(alice, id)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "invalid-state"));
  }

  // ---------------------------------------------------------------- payment outcomes

  @Test
  void should_answer_402_when_declined_and_let_the_customer_pay_again() throws Exception {
    UUID id = booked(alice, 1);

    pay(alice, id, "tok_declined")
        .andExpect(status().isPaymentRequired())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM))
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "payment-declined"));
    fetch(alice, id)
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.payment").doesNotExist());

    pay(alice, id, "tok_approved")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void should_answer_409_payment_in_progress_when_the_charge_gets_no_answer() throws Exception {
    UUID id = booked(alice, 1);
    gateway.failNextChargesBeforeProcessing(1);

    pay(alice, id, "tok_approved")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "payment-in-progress"));
    fetch(alice, id).andExpect(jsonPath("$.payment").doesNotExist());
  }

  @Test
  void should_answer_200_to_the_first_pay_and_409_payment_in_progress_to_the_second_one()
      throws Exception {
    UUID id = booked(alice, 2);
    Gate gate = gateway.holdCharges();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> first =
          pool.submit(() -> pay(alice, id, "tok_approved").andReturn().getResponse().getStatus());
      gate.awaitArrival();

      pay(alice, id, "tok_approved")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.type").value(TYPE_BASE + "payment-in-progress"));
      gate.open();

      assertThat(first.get()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
    assertThat(gateway.distinctCharges()).isEqualTo(1);
    fetch(alice, id).andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void should_refund_and_answer_409_when_the_hold_ends_while_the_charge_is_running()
      throws Exception {
    UUID id = booked(alice, 2);
    Gate gate = gateway.holdCharges();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<MockHttpServletResponse> paying =
          pool.submit(() -> pay(alice, id, "tok_approved").andReturn().getResponse());
      gate.awaitArrival();
      clock.set(EXPIRES_AT);
      gate.open();

      MockHttpServletResponse response = paying.get();
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(contentOf(response)).contains(TYPE_BASE + "payment-refunded");
    } finally {
      pool.shutdownNow();
    }
    fetch(alice, id)
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.payment.status").value("REFUNDED"));
    assertThat(gateway.distinctRefunds()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- someone else's booking

  @Test
  void should_answer_404_to_get_pay_and_cancel_when_the_booking_belongs_to_someone_else()
      throws Exception {
    UUID id = booked(alice, 1);

    fetch(bob, id)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "not-found"));
    pay(bob, id, "tok_approved").andExpect(status().isNotFound());
    cancel(bob, id).andExpect(status().isNotFound());

    assertThat(gateway.chargeCalls()).isZero();
    fetch(alice, id).andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void should_answer_404_when_the_booking_does_not_exist() throws Exception {
    fetch(alice, uuid(404)).andExpect(status().isNotFound());
    pay(alice, uuid(404), "tok_approved").andExpect(status().isNotFound());
    cancel(alice, uuid(404)).andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------- business rules

  @Test
  void should_answer_422_when_a_booking_holds_more_tickets_than_allowed() throws Exception {
    create(
            alice,
            KEY,
            bookingBody(EVENT, new RequestedItem(VIP_ZONE, 3), new RequestedItem(GENERAL_ZONE, 4)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "max-tickets-exceeded"));
    assertThat(app.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_answer_422_when_a_zone_is_listed_twice() throws Exception {
    create(
            alice,
            KEY,
            bookingBody(EVENT, new RequestedItem(VIP_ZONE, 1), new RequestedItem(VIP_ZONE, 2)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "duplicate-zone"));
  }

  @Test
  void should_answer_422_when_the_zone_is_not_in_the_event() throws Exception {
    create(alice, KEY, bookingBody(EVENT, new RequestedItem(uuid(99), 1)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "zone-not-in-event"));
  }

  @Test
  void should_answer_422_when_the_event_is_cancelled_or_already_started() throws Exception {
    clock.set(EVENT_STARTS);
    createVip(alice, KEY, 1)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "event-not-on-sale"));

    clock.set(NOW);
    cancelTheEvent();
    createVip(alice, uuid(501), 1)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "event-not-on-sale"));
  }

  @Test
  void should_answer_404_when_the_event_is_unknown() throws Exception {
    create(alice, KEY, bookingBody(uuid(999), new RequestedItem(VIP_ZONE, 1)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "not-found"));
  }

  @Test
  void should_answer_409_when_the_zone_is_sold_out() throws Exception {
    app.tx.run(() -> app.inventory.reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 20))));

    createVip(alice, KEY, 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "insufficient-inventory"));
    assertThat(app.count("SELECT count(*) FROM booking")).isZero();
  }

  // ---------------------------------------------------------------- invalid input

  @Test
  void should_answer_400_when_an_id_in_the_path_is_not_a_uuid() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    BOOKINGS + "/not-a-uuid")
                .header("Authorization", "Bearer " + alice))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM))
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-error"));
  }

  @Test
  void should_answer_400_when_a_quantity_is_below_one_or_above_the_maximum() throws Exception {
    for (int quantity : new int[] {0, -1, 7}) {
      createVip(alice, UUID.randomUUID(), quantity)
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(PROBLEM))
          .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-error"))
          .andExpect(jsonPath("$.errors[0].field").isNotEmpty());
    }
  }

  @Test
  void should_answer_400_when_there_are_no_items_or_more_than_six() throws Exception {
    create(alice, uuid(501), "{\"eventId\":\"" + EVENT + "\",\"items\":[]}")
        .andExpect(status().isBadRequest());
    RequestedItem[] seven = new RequestedItem[7];
    for (int i = 0; i < seven.length; i++) {
      seven[i] = new RequestedItem(uuid(600 + i), 1);
    }
    create(alice, uuid(502), bookingBody(EVENT, seven))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-error"));
  }

  @Test
  void should_answer_400_when_the_body_is_not_json_or_lacks_the_event() throws Exception {
    create(alice, uuid(501), "{ this is not json")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM))
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-error"));
    create(alice, uuid(502), "{\"items\":[{\"zoneId\":\"" + VIP_ZONE + "\",\"quantity\":1}]}")
        .andExpect(status().isBadRequest());
    create(alice, uuid(503), "").andExpect(status().isBadRequest());
  }

  @Test
  void should_answer_400_when_the_idempotency_key_is_missing_or_not_a_uuid() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BOOKINGS)
                .header("Authorization", "Bearer " + alice)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(vipBody(1)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM))
        .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BOOKINGS)
                .header("Authorization", "Bearer " + alice)
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(vipBody(1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-error"));
    assertThat(app.count("SELECT count(*) FROM booking")).isZero();
  }

  @Test
  void should_answer_400_when_the_payment_token_does_not_start_with_tok() throws Exception {
    UUID id = booked(alice, 1);

    pay(alice, id, "approved")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "validation-error"));
    pay(alice, id, "4111111111111111").andExpect(status().isBadRequest());
    payRaw(alice, id, "{}").andExpect(status().isBadRequest());
    payRaw(alice, id, "not json").andExpect(status().isBadRequest());
    assertThat(gateway.chargeCalls()).isZero();
  }

  // ---------------------------------------------------------------- authentication

  @Test
  void should_answer_401_as_problem_json_when_the_token_is_missing_invalid_or_unusable()
      throws Exception {
    UUID id = booked(alice, 1);
    String[] bad = {
      null,
      "garbage",
      com.eventhub.booking.support.TestTokens.expired(USER),
      com.eventhub.booking.support.TestTokens.signedWithUntrustedKey(USER),
      com.eventhub.booking.support.TestTokens.withSubject("alice-not-a-uuid")
    };

    for (String token : bad) {
      var request =
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
              BOOKINGS + "/" + id);
      if (token != null) {
        request.header("Authorization", "Bearer " + token);
      }
      mvc.perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(PROBLEM))
          .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
          .andExpect(jsonPath("$.type").value(endsWith("/unauthorized")));
    }
  }

  @Test
  void should_not_start_a_charge_for_a_request_without_a_valid_token() throws Exception {
    UUID id = booked(alice, 1);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    BOOKINGS + "/" + id + "/pay")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"paymentToken\":\"tok_approved\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(gateway.chargeCalls()).isZero();
  }

  // ---------------------------------------------------------------- availability

  @Test
  void should_show_the_availability_of_an_event_without_a_token() throws Exception {
    booked(alice, 5);

    availability(EVENT)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
        .andExpect(jsonPath("$.onSale").value(true))
        .andExpect(jsonPath("$.zones[?(@.name=='VIP')].available").value(15))
        .andExpect(jsonPath("$.zones[?(@.name=='VIP')].capacity").value(20))
        .andExpect(jsonPath("$.zones[?(@.name=='VIP')].price.amount").value("150.00"))
        .andExpect(jsonPath("$.zones[?(@.name=='General')].available").value(100));
  }

  @Test
  void should_answer_404_for_an_unknown_event_and_for_one_known_only_as_cancelled()
      throws Exception {
    availability(uuid(999))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    app.tx.run(() ->
        app.projections.save(
            com.eventhub.booking.domain.EventProjection.cancelledTombstone(uuid(998), NOW)));

    availability(uuid(998)).andExpect(status().isNotFound());
  }

  @Test
  void should_report_a_started_event_as_not_on_sale() throws Exception {
    clock.set(EVENT_STARTS);

    availability(EVENT).andExpect(status().isOk()).andExpect(jsonPath("$.onSale").value(false));
  }
}
