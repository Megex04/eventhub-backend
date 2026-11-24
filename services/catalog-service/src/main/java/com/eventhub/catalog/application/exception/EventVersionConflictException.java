package com.eventhub.catalog.application.exception;

import java.util.UUID;

/** Another transaction changed the event after it was loaded (maps to HTTP 409). */
public final class EventVersionConflictException extends ApplicationException {

  private final UUID eventId;

  public EventVersionConflictException(UUID eventId) {
    super("Event %s was modified concurrently; reload it and retry".formatted(eventId));
    this.eventId = eventId;
  }

  public UUID eventId() {
    return eventId;
  }
}
