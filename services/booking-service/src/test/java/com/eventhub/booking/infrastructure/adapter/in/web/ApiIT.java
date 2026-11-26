package com.eventhub.booking.infrastructure.adapter.in.web;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.eventhub.booking.application.port.in.ExpireBookingsUseCase;
import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.MutableClock;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import com.eventhub.booking.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The HTTP layer end to end: the real application context, a real PostgreSQL, real JWT validation,
 * a clock the test moves and a payment gateway the test drives. {@code alice} and {@code bob} are
 * two customers with no special role.
 */
@PersistenceIT
@AutoConfigureMockMvc
abstract class ApiIT {

  static final String BOOKINGS = "/api/v1/bookings";
  static final String PROBLEM = "application/problem+json";
  static final String TYPE_BASE = "https://eventhub.dev/problems/";

  @Autowired protected MockMvc mvc;
  @Autowired protected ApplicationContext context;
  @Autowired protected MutableClock clock;
  @Autowired protected FakePaymentGateway gateway;
  @Autowired protected ExpireBookingsUseCase expire;

  protected RealApp app;
  protected final String alice = TestTokens.forUser(USER);
  protected final String bob = TestTokens.forUser(OTHER_USER);

  @BeforeEach
  void openTheEvent() {
    app = new RealApp(context);
    app.openEvent(20, 100);
  }

  // ---- requests ----

  protected static String bookingBody(UUID eventId, RequestedItem... items) {
    StringBuilder json = new StringBuilder("{\"eventId\":\"" + eventId + "\",\"items\":[");
    for (int i = 0; i < items.length; i++) {
      json.append(i == 0 ? "" : ",")
          .append("{\"zoneId\":\"")
          .append(items[i].zoneId())
          .append("\",\"quantity\":")
          .append(items[i].quantity())
          .append("}");
    }
    return json.append("]}").toString();
  }

  /** Raw JSON on purpose: the tests also send quantities the domain would refuse to build. */
  protected static String vipBody(int quantity) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"items\":[{\"zoneId\":\""
        + VIP_ZONE
        + "\",\"quantity\":"
        + quantity
        + "}]}";
  }

  protected ResultActions create(String token, UUID key, String body) throws Exception {
    return mvc.perform(
        post(BOOKINGS)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  protected ResultActions createVip(String token, UUID key, int quantity) throws Exception {
    return create(token, key, vipBody(quantity));
  }

  protected ResultActions pay(String token, UUID bookingId, String paymentToken) throws Exception {
    return payRaw(token, bookingId, "{\"paymentToken\":\"" + paymentToken + "\"}");
  }

  protected ResultActions payRaw(String token, UUID bookingId, String body) throws Exception {
    return mvc.perform(
        post(BOOKINGS + "/" + bookingId + "/pay")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  protected ResultActions cancel(String token, UUID bookingId) throws Exception {
    return mvc.perform(
        post(BOOKINGS + "/" + bookingId + "/cancel").header("Authorization", "Bearer " + token));
  }

  protected ResultActions fetch(String token, UUID bookingId) throws Exception {
    return mvc.perform(get(BOOKINGS + "/" + bookingId).header("Authorization", "Bearer " + token));
  }

  protected ResultActions list(String token, String query) throws Exception {
    return mvc.perform(get(BOOKINGS + query).header("Authorization", "Bearer " + token));
  }

  protected ResultActions availability(UUID eventId) throws Exception {
    return mvc.perform(get("/api/v1/availability/" + eventId));
  }

  // ---- results ----

  protected static UUID idOf(MvcResult result) throws Exception {
    return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
  }

  /** Creates a booking of {@code quantity} VIP tickets for {@code token} and returns its id. */
  protected UUID booked(String token, int quantity) throws Exception {
    return idOf(createVip(token, UUID.randomUUID(), quantity).andReturn());
  }

  protected void cancelTheEvent() {
    app.tx.run(() -> {
      EventProjection projection = app.projections.findById(EVENT).orElseThrow();
      projection.applyCancelled(NOW.plusSeconds(1));
      app.projections.save(projection);
    });
  }
}
