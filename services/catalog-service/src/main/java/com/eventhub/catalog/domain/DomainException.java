package com.eventhub.catalog.domain;

/** Base type of every violation raised by the domain; the web adapter maps each subtype to HTTP. */
public abstract class DomainException extends RuntimeException {

  protected DomainException(String message) {
    super(message);
  }
}
