package com.eventhub.catalog.infrastructure.adapter.in.web;

import com.eventhub.catalog.application.exception.AuthenticationRequiredException;
import com.eventhub.catalog.application.exception.EventAccessDeniedException;
import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.domain.BusinessRuleViolationException;
import com.eventhub.catalog.domain.InvalidStateException;
import com.eventhub.catalog.domain.InvalidValueException;
import com.eventhub.catalog.domain.ZoneNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that turns failures into HTTP. Every response is RFC 9457 {@code
 * application/problem+json}; stack traces and exception internals never reach the client.
 *
 * <ul>
 *   <li>400 malformed or invalid field values
 *   <li>401 / 403 missing identity / missing role or foreign event
 *   <li>404 unknown event or zone
 *   <li>409 operation not allowed in the current state, or a concurrent change
 *   <li>422 a business rule is not met although the request is well-formed
 * </ul>
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String PROBLEM_TYPE_BASE = "https://eventhub.dev/problems/";
  private static final String TRACE_ID_KEY = "traceId";
  private static final String BEARER_CHALLENGE = "Bearer";

  /** One entry of the {@code errors} extension member of a validation problem. */
  public record FieldViolation(String field, String message) {}

  // ---- domain and application failures ----

  @ExceptionHandler(InvalidValueException.class)
  ResponseEntity<ProblemDetail> handleInvalidValue(
      InvalidValueException ex, HttpServletRequest request) {
    return validationFailure(
        List.of(new FieldViolation(ex.field(), ex.getMessage())), request.getRequestURI());
  }

  @ExceptionHandler(InvalidStateException.class)
  ResponseEntity<ProblemDetail> handleInvalidState(
      InvalidStateException ex, HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.CONFLICT,
            "invalid-state",
            "Invalid state",
            ex.getMessage(),
            request.getRequestURI()));
  }

  @ExceptionHandler(EventVersionConflictException.class)
  ResponseEntity<ProblemDetail> handleVersionConflict(
      EventVersionConflictException ex, HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.CONFLICT,
            "concurrent-modification",
            "Concurrent modification",
            ex.getMessage(),
            request.getRequestURI()));
  }

  @ExceptionHandler(BusinessRuleViolationException.class)
  ResponseEntity<ProblemDetail> handleBusinessRule(
      BusinessRuleViolationException ex, HttpServletRequest request) {
    String slug = ex.rule().name().toLowerCase(Locale.ROOT).replace('_', '-');
    return respond(
        problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            slug,
            "Business rule violated",
            ex.getMessage(),
            request.getRequestURI()));
  }

  @ExceptionHandler({EventNotFoundException.class, ZoneNotFoundException.class})
  ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex, HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.NOT_FOUND,
            "not-found",
            "Resource not found",
            ex.getMessage(),
            request.getRequestURI()));
  }

  // The message of the exception says whose event it is; the client only learns it is not theirs.
  @ExceptionHandler(EventAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleEventAccessDenied(
      EventAccessDeniedException ex, HttpServletRequest request) {
    return respond(forbidden("You are not allowed to modify this event", request));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    return respond(forbidden("You do not have permission to perform this operation", request));
  }

  @ExceptionHandler(AuthenticationRequiredException.class)
  ResponseEntity<ProblemDetail> handleAuthenticationRequired(
      AuthenticationRequiredException ex, HttpServletRequest request) {
    return unauthorized(ex.getMessage(), request);
  }

  // Never echo the cause (expired, bad signature, ...): it helps an attacker more than a client.
  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ProblemDetail> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    return unauthorized("A valid bearer token is required", request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return respond(
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Unexpected error",
            "The request could not be processed",
            request.getRequestURI()));
  }

  // ---- request binding and validation failures raised by Spring MVC ----

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldViolation> violations =
        ex.getBindingResult().getAllErrors().stream()
            .map(error ->
                new FieldViolation(
                    error instanceof org.springframework.validation.FieldError field
                        ? field.getField()
                        : error.getObjectName(),
                    error.getDefaultMessage()))
            .toList();
    return validationFailure(ex, violations, headers, request);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "validation-error",
            "Validation failed",
            "The request body is missing or malformed",
            requestUri(request));
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    List<FieldViolation> violations =
        List.of(new FieldViolation(ex.getPropertyName(), "The value has an invalid format"));
    return validationFailure(ex, violations, headers, request);
  }

  /**
   * Query parameters (page, size, q) are validated by the proxy that the generated interfaces'
   * {@code @Validated} creates, which raises this rather than Spring MVC's own validation error.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<FieldViolation> violations =
        ex.getConstraintViolations().stream()
            .map(violation -> new FieldViolation(leafName(violation), violation.getMessage()))
            .toList();
    return validationFailure(violations, request.getRequestURI());
  }

  // ---- helpers ----

  private ResponseEntity<ProblemDetail> validationFailure(
      List<FieldViolation> violations, String requestUri) {
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "validation-error",
            "Validation failed",
            "One or more fields are invalid",
            requestUri);
    problem.setProperty("errors", violations);
    return respond(problem);
  }

  private ResponseEntity<Object> validationFailure(
      Exception ex, List<FieldViolation> violations, HttpHeaders headers, WebRequest request) {
    ResponseEntity<ProblemDetail> problem = validationFailure(violations, requestUri(request));
    return handleExceptionInternal(ex, problem.getBody(), headers, HttpStatus.BAD_REQUEST, request);
  }

  private static ResponseEntity<ProblemDetail> unauthorized(
      String detail, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.UNAUTHORIZED,
            "unauthorized",
            "Authentication required",
            detail,
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
        .body(problem);
  }

  private static ProblemDetail forbidden(String detail, HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN, "forbidden", "Access denied", detail, request.getRequestURI());
  }

  private static ProblemDetail problem(
      HttpStatus status, String slug, String title, String detail, String requestUri) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEM_TYPE_BASE + slug));
    problem.setTitle(title);
    if (requestUri != null) {
      problem.setInstance(URI.create(requestUri));
    }
    String traceId = MDC.get(TRACE_ID_KEY);
    if (traceId != null) {
      problem.setProperty(TRACE_ID_KEY, traceId);
    }
    return problem;
  }

  private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
    return ResponseEntity.status(problem.getStatus()).body(problem);
  }

  private static String requestUri(WebRequest request) {
    return request instanceof ServletWebRequest servletRequest
        ? servletRequest.getRequest().getRequestURI()
        : null;
  }

  private static String leafName(ConstraintViolation<?> violation) {
    String name = "";
    for (Path.Node node : violation.getPropertyPath()) {
      name = node.getName();
    }
    return name;
  }
}
