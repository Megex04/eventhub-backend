package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.DomainEvent;
import java.util.List;

/**
 * Hands domain events to the outbox. It must be called inside the transaction that saved the
 * aggregate, so the state change and its events commit or roll back together.
 */
public interface DomainEventPublisher {

  void publish(List<DomainEvent> events);
}
