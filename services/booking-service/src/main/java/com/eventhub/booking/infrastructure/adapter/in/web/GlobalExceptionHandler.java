package com.eventhub.booking.infrastructure.adapter.in.web;

import com.eventhub.booking.application.exception.IdempotencyKeyReuseException;
import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.exception.PaymentDeclinedException;
import com.eventhub.booking.application.exception.PaymentRefundPendingException;
import com.eventhub.booking.application.exception.PaymentRefundedException;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.DuplicateIdempotencyKeyException;
import com.eventhub.booking.application.port.out.PersistenceFailureException;
import com.eventhub.booking.domain.BusinessRuleViolationException;
import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.InvalidStateException;
import com.eventhub.booking.domain.InvalidValueException;
import com.eventhub.booking.domain.InventoryInvariantException;
import com.eventhub.booking.domain.PaymentInProgressException;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that turns failures into HTTP. Every response is RFC 9457 {@code
 * application/problem+json} with a {@code type} of its own per rule; stack traces and exception
 * internals never reach the client.
 *
 * <ul>
 *   <li>400 malformed or invalid input (fields, headers, ids)
 *   <li>401 / 403 missing or invalid identity / not allowed
 *   <li>402 the gateway declined the payment
 *   <li>404 unknown resource, or a booking that is someone else's
 *   <li>409 the state does not allow it, a concurrent change, tickets sold out, payment in progress
 *       or refunded, an Idempotency-Key reused with another body
 *   <li>422 a business rule is not met although the request is well-formed (one type per rule)
 *   <li>500 anything unexpected, and the one failure that signals a bug in the inventory
 * </ul>
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String PROBLEM_TYPE_BASE = "https://eventhub.dev/problems/";
  private static final String TRACE_ID_KEY = "traceId";
  private static final String BEARER_CHALLENGE = "Bearer";
  static final String INVARIANT_METRIC = "booking.inventory.invariant.violations";

  /** One entry of the {@code errors} extension member of a validation problem. */
  public record FieldViolation(String field, String message) {}

  private final MeterRegistry meters;

  GlobalExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

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
    return conflict("invalid-state", "Invalid state", ex.getMessage(), request);
  }

  @ExceptionHandler(PaymentInProgressException.class)
  ResponseEntity<ProblemDetail> handlePaymentInProgress(
      PaymentInProgressException ex, HttpServletRequest request) {
    return conflict("payment-in-progress", "Payment in progress", ex.getMessage(), request);
  }

  @ExceptionHandler(PaymentRefundedException.class)
  ResponseEntity<ProblemDetail> handlePaymentRefunded(
      PaymentRefundedException ex, HttpServletRequest request) {
    return conflict("payment-refunded", "Payment refunded", ex.getMessage(), request);
  }

  @ExceptionHandler(PaymentRefundPendingException.class)
  ResponseEntity<ProblemDetail> handlePaymentRefundPending(
      PaymentRefundPendingException ex, HttpServletRequest request) {
    return conflict("payment-refund-pending", "Payment refund pending", ex.getMessage(), request);
  }

  /** The use case already decided what to do; this is only for a failure that ends the request. */
  @ExceptionHandler(PersistenceFailureException.class)
  ResponseEntity<ProblemDetail> handlePersistenceFailure(
      PersistenceFailureException ex, HttpServletRequest request) {
    log.error("Persistence failure on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return internalError(request);
  }

  @ExceptionHandler(InsufficientInventoryException.class)
  ResponseEntity<ProblemDetail> handleInsufficientInventory(
      InsufficientInventoryException ex, HttpServletRequest request) {
    return conflict("insufficient-inventory", "Insufficient inventory", ex.getMessage(), request);
  }

  @ExceptionHandler(IdempotencyKeyReuseException.class)
  ResponseEntity<ProblemDetail> handleIdempotencyKeyReuse(
      IdempotencyKeyReuseException ex, HttpServletRequest request) {
    return conflict("idempotency-key-reuse", "Idempotency-Key reused", ex.getMessage(), request);
  }

  @ExceptionHandler({ConcurrencyConflictException.class, DuplicateIdempotencyKeyException.class})
  ResponseEntity<ProblemDetail> handleConcurrencyConflict(
      RuntimeException ex, HttpServletRequest request) {
    return conflict(
        "concurrency-conflict",
        "Concurrent modification",
        "The booking was changed by another request; reload it and try again",
        request);
  }

  @ExceptionHandler(PaymentDeclinedException.class)
  ResponseEntity<ProblemDetail> handlePaymentDeclined(
      PaymentDeclinedException ex, HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.PAYMENT_REQUIRED,
            "payment-declined",
            "Payment declined",
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

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.NOT_FOUND,
            "not-found",
            "Resource not found",
            ex.getMessage(),
            request.getRequestURI()));
  }

  /**
   * Releasing more tickets than were reserved means the books no longer add up: a bug, not a user
   * error. The client learns nothing; the log and the metric are how operators find out.
   */
  @ExceptionHandler(InventoryInvariantException.class)
  ResponseEntity<ProblemDetail> handleInventoryInvariant(
      InventoryInvariantException ex, HttpServletRequest request) {
    log.error(
        "Inventory invariant violated on {} {}", request.getMethod(), request.getRequestURI(), ex);
    meters.counter(INVARIANT_METRIC).increment();
    return internalError(request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.FORBIDDEN,
            "forbidden",
            "Access denied",
            "You do not have permission to perform this operation",
            request.getRequestURI()));
  }

  // Never echo the cause (expired, bad signature, ...): it helps an attacker more than a client.
  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ProblemDetail> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.UNAUTHORIZED,
            "unauthorized",
            "Authentication required",
            "A valid bearer token is required",
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
        .body(problem);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return internalError(request);
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
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldViolation> violations =
        ex.getParameterValidationResults().stream()
            .flatMap(result ->
                result.getResolvableErrors().stream()
                    .map(error ->
                        new FieldViolation(
                            String.valueOf(result.getMethodParameter().getParameterName()),
                            error.getDefaultMessage())))
            .toList();
    return validationFailure(ex, violations, headers, request);
  }

  @Override
  protected ResponseEntity<Object> handleServletRequestBindingException(
      ServletRequestBindingException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String field =
        ex instanceof MissingRequestHeaderException header ? header.getHeaderName() : "request";
    return validationFailure(
        ex,
        List.of(new FieldViolation(field, "The value is missing or invalid")),
        headers,
        request);
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
    String field =
        ex instanceof MethodArgumentTypeMismatchException argument
            ? argument.getName()
            : String.valueOf(ex.getPropertyName());
    return validationFailure(
        ex,
        List.of(new FieldViolation(field, "The value has an invalid format")),
        headers,
        request);
  }

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

  private ResponseEntity<ProblemDetail> conflict(
      String slug, String title, String detail, HttpServletRequest request) {
    return respond(problem(HttpStatus.CONFLICT, slug, title, detail, request.getRequestURI()));
  }

  private ResponseEntity<ProblemDetail> internalError(HttpServletRequest request) {
    return respond(
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Unexpected error",
            "The request could not be processed",
            request.getRequestURI()));
  }

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
