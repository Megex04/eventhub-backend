package com.eventhub.catalog.application.port.out;

import com.eventhub.catalog.domain.DomainEvent;
import java.util.List;

/**
 * Hands domain events to the messaging side. Implementations must take part in the caller's
 * transaction (transactional outbox) so an event is never lost nor emitted for a rolled-back
 * change.
 */
public interface DomainEventPublisher {

  void publish(List<DomainEvent> events);
}
