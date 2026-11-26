package com.eventhub.booking.infrastructure.adapter.in.web;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.eventhub.booking.application.AppFixtures.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.eventhub.booking.application.port.out.FakePaymentGateway.Gate;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.contracttesting.OpenApiContracts;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every response the service gives is checked against {@code contracts/openapi/booking-api.yaml}:
 * the status must be declared for that operation, and content type, headers and body (problem+json
 * included) must match the declared schema. A status the code returns but the contract does not
 * declare fails here.
 */
class OpenApiConformanceIT extends ApiIT {

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiContracts.validatorFor("booking-api.yaml");

  private static ResultActions conforms(ResultActions result, int status) throws Exception {
    return result.andExpect(status().is(status)).andExpect(openApi().isValid(VALIDATOR));
  }

  // ---- createBooking: 201 200 400 401 404 409 422 ----

  @Test
  void should_conform_when_creating_bookings() throws Exception {
    UUID key = uuid(500);
    conforms(createVip(alice, key, 2), 201);
    conforms(createVip(alice, key, 2), 200);
    conforms(createVip(alice, uuid(501), 0), 400);
    conforms(create(alice, uuid(502), "not json"), 400);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BOOKINGS)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(vipBody(1))),
        401);
    conforms(create(alice, uuid(503), bookingBody(uuid(999), new RequestedItem(VIP_ZONE, 1))), 404);
    conforms(createVip(alice, key, 3), 409);
    conforms(
        create(
            alice,
            uuid(504),
            bookingBody(EVENT, new RequestedItem(VIP_ZONE, 3), new RequestedItem(GENERAL_ZONE, 4))),
        422);
  }

  @Test
  void should_conform_when_the_zone_is_sold_out() throws Exception {
    app.tx.run(
        () -> app.inventory.reserve(EVENT, java.util.List.of(new RequestedItem(VIP_ZONE, 20))));

    conforms(createVip(alice, uuid(500), 1), 409);
  }

  // ---- listMyBookings: 200 400 401 ----

  @Test
  void should_conform_when_listing_bookings() throws Exception {
    booked(alice, 1);

    conforms(list(alice, ""), 200);
    conforms(list(alice, "?status=PENDING&page=0&size=10&sort=createdAt"), 200);
    conforms(list(alice, "?size=0"), 400);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BOOKINGS)),
        401);
  }

  // ---- getBooking: 200 401 404 ----

  @Test
  void should_conform_when_getting_a_booking() throws Exception {
    UUID id = booked(alice, 1);

    conforms(fetch(alice, id), 200);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                BOOKINGS + "/" + id)),
        401);
    conforms(fetch(alice, uuid(404)), 404);
    conforms(fetch(bob, id), 404);
  }

  // ---- payBooking: 200 400 401 402 404 409 ----

  @Test
  void should_conform_when_paying() throws Exception {
    UUID id = booked(alice, 1);
    UUID declined = booked(alice, 1);

    conforms(payRaw(alice, id, "{\"paymentToken\":\"nope\"}"), 400);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    BOOKINGS + "/" + id + "/pay")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"paymentToken\":\"tok_approved\"}")),
        401);
    conforms(pay(alice, declined, "tok_declined"), 402);
    conforms(pay(alice, uuid(404), "tok_approved"), 404);
    conforms(pay(bob, id, "tok_approved"), 404);
    conforms(pay(alice, id, "tok_approved"), 200);
    conforms(pay(alice, id, "tok_approved"), 200);
    clock.advance(HOLD);
    conforms(pay(alice, declined, "tok_approved"), 409);
  }

  @Test
  void should_conform_when_the_payment_is_in_progress_or_refunded() throws Exception {
    UUID inProgress = booked(alice, 1);
    gateway.failNextChargesBeforeProcessing(1);
    conforms(pay(alice, inProgress, "tok_approved"), 409);

    UUID refunded = booked(alice, 1);
    Gate gate = gateway.holdCharges();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> paying =
          pool.submit(
              () -> pay(alice, refunded, "tok_approved").andReturn().getResponse().getStatus());
      gate.awaitArrival();
      clock.advance(HOLD);
      gate.open();
      org.assertj.core.api.Assertions.assertThat(paying.get()).isEqualTo(409);
    } finally {
      pool.shutdownNow();
    }
    conforms(fetch(alice, refunded), 200);
  }

  // ---- cancelBooking: 200 401 404 409 422 ----

  @Test
  void should_conform_when_cancelling() throws Exception {
    UUID pending = booked(alice, 1);
    UUID confirmed = booked(alice, 1);
    pay(alice, confirmed, "tok_approved").andExpect(status().isOk());

    conforms(cancel(alice, pending), 200);
    conforms(cancel(alice, pending), 409);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                BOOKINGS + "/" + confirmed + "/cancel")),
        401);
    conforms(cancel(alice, uuid(404)), 404);
    clock.set(EVENT_STARTS);
    conforms(cancel(alice, confirmed), 422);
  }

  // ---- getEventAvailability: 200 404 ----

  @Test
  void should_conform_when_reading_the_availability() throws Exception {
    booked(alice, 3);

    conforms(availability(EVENT), 200);
    conforms(availability(uuid(999)), 404);
  }

  /**
   * GAP IN THE CONTRACT, awaiting a decision: a malformed id in the path is answered 400 (the
   * framework cannot bind it), but {@code getBooking}, {@code cancelBooking} and {@code
   * getEventAvailability} do not declare 400. CLAUDE.md asks the OpenAPI to declare every status
   * the service returns, and contracts/ is not changed without approval. Enable this test once the
   * three operations declare 400.
   */
  @Test
  @Disabled(
      "contract gap: 400 is not declared for getBooking, cancelBooking and getEventAvailability")
  void should_conform_when_an_id_in_the_path_is_malformed() throws Exception {
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    BOOKINGS + "/not-a-uuid")
                .header("Authorization", "Bearer " + alice)),
        400);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    BOOKINGS + "/not-a-uuid/cancel")
                .header("Authorization", "Bearer " + alice)),
        400);
    conforms(
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/availability/not-a-uuid")),
        400);
  }
}
