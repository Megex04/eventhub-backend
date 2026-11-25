package com.eventhub.booking.application.exception;

import java.util.UUID;

/**
 * The resource does not exist or is not visible to the caller (maps to HTTP 404). A booking of
 * another user is reported exactly like a missing one, so ids cannot be probed.
 */
public final class NotFoundException extends ApplicationException {

  private final String resource;
  private final UUID id;

  public NotFoundException(String resource, UUID id) {
    super("%s %s not found".formatted(resource, id));
    this.resource = resource;
    this.id = id;
  }

  public String resource() {
    return resource;
  }

  public UUID id() {
    return id;
  }
}
