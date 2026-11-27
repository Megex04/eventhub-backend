package com.eventhub.booking.application.exception;

/** Base of the failures that belong to a use case and not to the domain model. */
public abstract class ApplicationException extends RuntimeException {

  protected ApplicationException(String message) {
    super(message);
  }
}
