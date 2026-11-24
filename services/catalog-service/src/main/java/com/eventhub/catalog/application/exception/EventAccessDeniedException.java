package com.eventhub.catalog.application.exception;

import java.util.UUID;

/** The event is visible to the caller but belongs to another organizer (maps to HTTP 403). */
public final class EventAccessDeniedException extends ApplicationException {

  private final UUID eventId;

  public EventAccessDeniedException(UUID eventId) {
    super("Event %s belongs to another organizer".formatted(eventId));
    this.eventId = eventId;
  }

  public UUID eventId() {
    return eventId;
  }
}
