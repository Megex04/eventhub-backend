package com.eventhub.booking.infrastructure.adapter.out.messaging;

/** Sends one outbox row to the broker and returns only once the broker has confirmed it. */
public interface OutboxBroker {

  /**
   * @throws BrokerPublishException if the broker did not confirm the message
   */
  void publish(OutboxMessage message);
}
