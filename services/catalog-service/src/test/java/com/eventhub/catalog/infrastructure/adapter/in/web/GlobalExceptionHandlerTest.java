package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.exception.AuthenticationRequiredException;
import com.eventhub.catalog.application.exception.EventAccessDeniedException;
import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.domain.BusinessRuleViolationException;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.InvalidStateException;
import com.eventhub.catalog.domain.InvalidValueException;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.domain.ZoneNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class GlobalExceptionHandlerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String PROBLEMS = "https://eventhub.dev/problems/";

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/api/v1/events");

  @Test
  void should_answer_400_with_the_field_when_a_value_is_invalid() {
    ResponseEntity<ProblemDetail> response =
        handler.handleInvalidValue(
            new InvalidValueException("title", "title is too short"), request);

    assertProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(response.getBody().getProperties())
        .extractingByKey("errors")
        .isEqualTo(
            List.of(new GlobalExceptionHandler.FieldViolation("title", "title is too short")));
  }

  @Test
  void should_answer_409_when_the_state_does_not_allow_the_operation() {
    InvalidStateException failure = invalidStateFromPublishingTwice();

    ResponseEntity<ProblemDetail> response = handler.handleInvalidState(failure, request);

    assertProblem(response, HttpStatus.CONFLICT, "invalid-state");
    assertThat(response.getBody().getDetail()).isEqualTo(failure.getMessage());
  }

  @Test
  void should_answer_409_when_the_event_was_modified_concurrently() {
    ResponseEntity<ProblemDetail> response =
        handler.handleVersionConflict(
            new EventVersionConflictException(UUID.randomUUID()), request);

    assertProblem(response, HttpStatus.CONFLICT, "concurrent-modification");
  }

  @Test
  void should_answer_422_with_a_type_per_rule_when_a_business_rule_fails() {
    BusinessRuleViolationException failure = ruleViolationFromEventWithoutZones();

    ResponseEntity<ProblemDetail> response = handler.handleBusinessRule(failure, request);

    assertProblem(response, HttpStatus.UNPROCESSABLE_CONTENT, "event-without-zones");
  }

  @Test
  void should_answer_404_when_the_event_does_not_exist() {
    ResponseEntity<ProblemDetail> response =
        handler.handleNotFound(new EventNotFoundException(UUID.randomUUID()), request);

    assertProblem(response, HttpStatus.NOT_FOUND, "not-found");
  }

  @Test
  void should_answer_404_when_the_zone_does_not_belong_to_the_event() {
    ResponseEntity<ProblemDetail> response =
        handler.handleNotFound(new ZoneNotFoundException(UUID.randomUUID()), request);

    assertProblem(response, HttpStatus.NOT_FOUND, "not-found");
  }

  @Test
  void should_answer_403_without_revealing_the_owner_when_the_event_is_not_yours() {
    ResponseEntity<ProblemDetail> response =
        handler.handleEventAccessDenied(new EventAccessDeniedException(UUID.randomUUID()), request);

    assertProblem(response, HttpStatus.FORBIDDEN, "forbidden");
    assertThat(response.getBody().getDetail()).doesNotContain("organizer");
  }

  @Test
  void should_answer_403_when_security_denies_access() {
    ResponseEntity<ProblemDetail> response =
        handler.handleAccessDenied(new AccessDeniedException("Access Denied"), request);

    assertProblem(response, HttpStatus.FORBIDDEN, "forbidden");
  }

  @Test
  void should_answer_401_with_a_bearer_challenge_when_authentication_is_missing() {
    ResponseEntity<ProblemDetail> response =
        handler.handleAuthenticationRequired(
            new AuthenticationRequiredException("Listing your own events requires authentication"),
            request);

    assertProblem(response, HttpStatus.UNAUTHORIZED, "unauthorized");
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
  }

  @Test
  void should_answer_401_without_echoing_the_cause_when_the_token_is_rejected() {
    ResponseEntity<ProblemDetail> response =
        handler.handleAuthentication(new BadCredentialsException("signature mismatch"), request);

    assertProblem(response, HttpStatus.UNAUTHORIZED, "unauthorized");
    assertThat(response.getBody().getDetail()).doesNotContain("signature");
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
  }

  @Test
  void should_answer_500_without_leaking_internals_when_the_failure_is_unexpected() {
    ResponseEntity<ProblemDetail> response =
        handler.handleUnexpected(
            new IllegalStateException("jdbc:postgresql://secret-host/db"), request);

    assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
    assertThat(response.getBody().getDetail()).doesNotContain("secret-host");
  }

  @Test
  void should_point_the_instance_at_the_request_path_when_building_any_problem() {
    ResponseEntity<ProblemDetail> response =
        handler.handleNotFound(new EventNotFoundException(UUID.randomUUID()), request);

    assertThat(response.getBody().getInstance()).hasToString("/api/v1/events");
  }

  private static void assertProblem(
      ResponseEntity<ProblemDetail> response, HttpStatus status, String typeSlug) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getStatus()).isEqualTo(status.value());
    assertThat(body.getType()).hasToString(PROBLEMS + typeSlug);
    assertThat(body.getTitle()).isNotBlank();
  }

  private static InvalidStateException invalidStateFromPublishingTwice() {
    Event event = eventWithoutZones();
    event.addZone(
        TicketZone.create(UUID.randomUUID(), "General", Money.of("10.00", "PEN"), 10), CLOCK);
    event.publish(CLOCK);
    try {
      event.publish(CLOCK);
    } catch (InvalidStateException expected) {
      return expected;
    }
    throw new AssertionError("publishing a published event must fail");
  }

  private static BusinessRuleViolationException ruleViolationFromEventWithoutZones() {
    Event event = eventWithoutZones();
    try {
      event.publish(CLOCK);
    } catch (BusinessRuleViolationException expected) {
      return expected;
    }
    throw new AssertionError("publishing an event without zones must fail");
  }

  private static Event eventWithoutZones() {
    Instant startsAt = NOW.plus(Duration.ofDays(30));
    return Event.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Rock Fest",
        "Open air",
        "Estadio Nacional",
        startsAt,
        startsAt.plus(Duration.ofHours(4)),
        List.of(),
        CLOCK);
  }
}
