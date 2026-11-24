package com.eventhub.catalog.application.exception;

/** A public route was asked for something that needs an identity (maps to HTTP 401). */
public final class AuthenticationRequiredException extends ApplicationException {

  public AuthenticationRequiredException(String message) {
    super(message);
  }
}
