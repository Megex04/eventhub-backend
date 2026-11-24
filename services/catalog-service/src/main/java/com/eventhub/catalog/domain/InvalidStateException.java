package com.eventhub.catalog.domain;

/** The operation is not allowed in the event's current lifecycle state (maps to HTTP 409). */
public final class InvalidStateException extends DomainException {

  private final EventStatus currentStatus;

  private InvalidStateException(String message, EventStatus currentStatus) {
    super(message);
    this.currentStatus = currentStatus;
  }

  static InvalidStateException notAllowed(String action, EventStatus currentStatus) {
    return new InvalidStateException(
        "Cannot %s an event in status %s".formatted(action, currentStatus), currentStatus);
  }

  public EventStatus currentStatus() {
    return currentStatus;
  }
}
