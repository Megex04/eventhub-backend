package com.eventhub.catalog.application.exception;

/** Base type of failures that belong to the use-case layer rather than to the domain model. */
public abstract class ApplicationException extends RuntimeException {

  protected ApplicationException(String message) {
    super(message);
  }
}
