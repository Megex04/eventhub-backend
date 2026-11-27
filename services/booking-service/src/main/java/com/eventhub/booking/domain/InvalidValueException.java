package com.eventhub.booking.domain;

/** A field value is malformed or out of range (maps to HTTP 400). */
public final class InvalidValueException extends DomainException {

  private final String field;

  public InvalidValueException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
