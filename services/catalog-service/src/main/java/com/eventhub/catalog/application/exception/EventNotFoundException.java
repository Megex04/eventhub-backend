package com.eventhub.catalog.application.exception;

import java.util.UUID;

/** The event does not exist or the caller is not allowed to know it exists (maps to HTTP 404). */
public final class EventNotFoundException extends ApplicationException {

  private final UUID eventId;

  public EventNotFoundException(UUID eventId) {
    super("Event %s not found".formatted(eventId));
    this.eventId = eventId;
  }

  public UUID eventId() {
    return eventId;
  }
}
