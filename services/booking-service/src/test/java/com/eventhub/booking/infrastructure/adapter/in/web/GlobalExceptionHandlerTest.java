package com.eventhub.booking.infrastructure.adapter.in.web;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.application.exception.IdempotencyKeyReuseException;
import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.exception.PaymentDeclinedException;
import com.eventhub.booking.application.exception.PaymentRefundPendingException;
import com.eventhub.booking.application.exception.PaymentRefundedException;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.DuplicateIdempotencyKeyException;
import com.eventhub.booking.application.port.out.PersistenceFailureException;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.InvalidValueException;
import com.eventhub.booking.domain.InventoryInvariantException;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.PaymentInProgressException;
import com.eventhub.booking.domain.ProjectedZone;
import com.eventhub.booking.domain.RequestedItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Every failure the service can raise, mapped to its status and its own problem type. */
class GlobalExceptionHandlerTest {

  private static final Supplier<RuntimeException> NEVER = () -> new IllegalStateException("unused");

  /** Raises whatever the registered supplier builds, by name. */
  @RestController
  static class Boom {
    static volatile Supplier<RuntimeException> next = NEVER;

    @GetMapping("/boom")
    void boom() {
      throw next.get();
    }

    @GetMapping("/typed/{id}")
    void typed(@PathVariable UUID id) {}
  }

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Boom())
          .setControllerAdvice(new GlobalExceptionHandler(meters))
          .build();

  private static RuntimeException rule(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      return expected;
    }
    throw new AssertionError("the action was expected to fail");
  }

  private static Booking booking() {
    return newBooking(uuid(1), USER, at(NOW), new RequestedItem(VIP_ZONE, 1));
  }

  static Stream<Arguments> failures() {
    EventProjection event = openProjection();
    ProjectedZone dollars = new ProjectedZone(GENERAL_ZONE, "General", Money.of("1.00", "USD"), 5);
    EventProjection mixed =
        EventProjection.published(
            EVENT, EVENT_STARTS, EVENT_STARTS.plusSeconds(3600), List.of(VIP, dollars), NOW);
    EventProjection cancelled = openProjection();
    cancelled.applyCancelled(NOW.plusSeconds(1));
    return Stream.of(
        Arguments.of(
            "invalid value",
            (Supplier<RuntimeException>)
                () -> new InvalidValueException("quantity", "quantity must be at least 1"),
            400,
            "validation-error"),
        Arguments.of(
            "invalid state",
            (Supplier<RuntimeException>) () -> rule(() -> booking().expire(at(NOW))),
            409,
            "invalid-state"),
        Arguments.of(
            "payment in progress",
            (Supplier<RuntimeException>) () -> new PaymentInProgressException(uuid(9)),
            409,
            "payment-in-progress"),
        Arguments.of(
            "payment refund pending",
            (Supplier<RuntimeException>) PaymentRefundPendingException::new,
            409,
            "payment-refund-pending"),
        Arguments.of(
            "persistence failure",
            (Supplier<RuntimeException>)
                () -> new PersistenceFailureException("db down", new RuntimeException("secret")),
            500,
            "internal-error"),
        Arguments.of(
            "payment refunded",
            (Supplier<RuntimeException>) PaymentRefundedException::new,
            409,
            "payment-refunded"),
        Arguments.of(
            "insufficient inventory",
            (Supplier<RuntimeException>) () -> new InsufficientInventoryException(VIP_ZONE, 3, 1),
            409,
            "insufficient-inventory"),
        Arguments.of(
            "key reuse",
            (Supplier<RuntimeException>) () -> new IdempotencyKeyReuseException(uuid(5)),
            409,
            "idempotency-key-reuse"),
        Arguments.of(
            "concurrency conflict",
            (Supplier<RuntimeException>) () -> new ConcurrencyConflictException(uuid(1)),
            409,
            "concurrency-conflict"),
        Arguments.of(
            "duplicate key that escaped",
            (Supplier<RuntimeException>) () -> new DuplicateIdempotencyKeyException(USER, uuid(5)),
            409,
            "concurrency-conflict"),
        Arguments.of(
            "payment declined",
            (Supplier<RuntimeException>) PaymentDeclinedException::new,
            402,
            "payment-declined"),
        Arguments.of(
            "max tickets",
            (Supplier<RuntimeException>)
                () ->
                    rule(() ->
                        Booking.create(
                            uuid(1),
                            USER,
                            event,
                            List.of(
                                new RequestedItem(VIP_ZONE, 4), new RequestedItem(GENERAL_ZONE, 3)),
                            POLICY,
                            at(NOW))),
            422,
            "max-tickets-exceeded"),
        Arguments.of(
            "duplicate zone",
            (Supplier<RuntimeException>)
                () ->
                    rule(() ->
                        Booking.create(
                            uuid(1),
                            USER,
                            event,
                            List.of(new RequestedItem(VIP_ZONE, 1), new RequestedItem(VIP_ZONE, 1)),
                            POLICY,
                            at(NOW))),
            422,
            "duplicate-zone"),
        Arguments.of(
            "zone not in event",
            (Supplier<RuntimeException>)
                () ->
                    rule(() ->
                        Booking.create(
                            uuid(1),
                            USER,
                            event,
                            List.of(new RequestedItem(uuid(99), 1)),
                            POLICY,
                            at(NOW))),
            422,
            "zone-not-in-event"),
        Arguments.of(
            "event not on sale",
            (Supplier<RuntimeException>)
                () ->
                    rule(() ->
                        Booking.create(
                            uuid(1),
                            USER,
                            cancelled,
                            List.of(new RequestedItem(VIP_ZONE, 1)),
                            POLICY,
                            at(NOW.plusSeconds(2)))),
            422,
            "event-not-on-sale"),
        Arguments.of(
            "mixed currency",
            (Supplier<RuntimeException>)
                () ->
                    rule(() ->
                        Booking.create(
                            uuid(1),
                            USER,
                            mixed,
                            List.of(
                                new RequestedItem(VIP_ZONE, 1), new RequestedItem(GENERAL_ZONE, 1)),
                            POLICY,
                            at(NOW))),
            422,
            "mixed-currency"),
        Arguments.of(
            "cancel after start",
            (Supplier<RuntimeException>)
                () ->
                    rule(() ->
                        confirmed()
                            .cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(EVENT_STARTS))),
            422,
            "cancel-after-start"),
        Arguments.of(
            "not found",
            (Supplier<RuntimeException>) () -> new NotFoundException("Booking", uuid(1)),
            404,
            "not-found"),
        Arguments.of(
            "unexpected",
            (Supplier<RuntimeException>) () -> new IllegalStateException("secret internal detail"),
            500,
            "internal-error"));
  }

  private static Booking confirmed() {
    Booking booking = booking();
    booking.beginPayment(uuid(7), "tok_approved", at(NOW));
    booking.capturePayment(at(NOW.plusSeconds(1)));
    return booking;
  }

  @ParameterizedTest(name = "{0} -> {2} {3}")
  @MethodSource("failures")
  void should_answer_with_the_status_and_type_of_the_rule(
      String name, Supplier<RuntimeException> failure, int status, String type) throws Exception {
    Boom.next = failure;

    mvc.perform(get("/boom"))
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("https://eventhub.dev/problems/" + type))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.title").isNotEmpty());
  }

  @Test
  void should_answer_500_without_details_and_count_it_when_the_inventory_invariant_breaks()
      throws Exception {
    Boom.next = () -> new InventoryInvariantException(VIP_ZONE, 1, 5);

    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.type").value("https://eventhub.dev/problems/internal-error"))
        .andExpect(jsonPath("$.detail").value("The request could not be processed"))
        .andExpect(content().string(not(containsString(VIP_ZONE.toString()))));

    assertThat(meters.counter(GlobalExceptionHandler.INVARIANT_METRIC).count()).isEqualTo(1.0);
  }

  @Test
  void should_not_leak_the_message_of_an_unexpected_failure() throws Exception {
    Boom.next = () -> new IllegalStateException("secret internal detail");

    mvc.perform(get("/boom"))
        .andExpect(content().string(not(containsString("secret internal detail"))));
  }

  @Test
  void should_answer_401_with_a_bearer_challenge_when_authentication_fails() throws Exception {
    Boom.next = () -> new BadCredentialsException("expired at 12:00");

    mvc.perform(get("/boom"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.type").value(endsWith("/unauthorized")))
        .andExpect(content().string(not(containsString("expired at"))));
  }

  @Test
  void should_answer_403_when_access_is_denied() throws Exception {
    Boom.next = () -> new AccessDeniedException("no");

    mvc.perform(get("/boom"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(endsWith("/forbidden")));
  }

  @Test
  void should_answer_400_with_the_field_when_an_id_in_the_path_is_not_a_uuid() throws Exception {
    mvc.perform(get("/typed/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(endsWith("/validation-error")))
        .andExpect(jsonPath("$.errors[0].field").value("id"));
  }

  @Test
  void should_not_count_ordinary_failures_as_inventory_violations() throws Exception {
    Boom.next = () -> new NotFoundException("Booking", uuid(1));

    mvc.perform(get("/boom")).andExpect(status().isNotFound());

    assertThat(meters.counter(GlobalExceptionHandler.INVARIANT_METRIC).count()).isZero();
  }
}
