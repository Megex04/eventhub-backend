package com.eventhub.booking.application.exception;

import java.util.UUID;

/**
 * A message refers to an event this service has not seen yet. It is retryable on purpose: the
 * consumer must fail so the message is redelivered with backoff, and parked in the DLQ if the
 * publication never arrives.
 */
public final class EventProjectionMissingException extends ApplicationException {

  public EventProjectionMissingException(UUID eventId) {
    super("No projection for event %s yet".formatted(eventId));
  }
}
