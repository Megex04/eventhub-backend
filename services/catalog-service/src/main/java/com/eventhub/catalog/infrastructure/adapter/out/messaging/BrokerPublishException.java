package com.eventhub.catalog.infrastructure.adapter.out.messaging;

/** The broker did not confirm a message (unreachable, nack, timeout). The row is retried later. */
public final class BrokerPublishException extends RuntimeException {

  public BrokerPublishException(String message, Throwable cause) {
    super(message, cause);
  }

  public BrokerPublishException(String message) {
    super(message);
  }
}
