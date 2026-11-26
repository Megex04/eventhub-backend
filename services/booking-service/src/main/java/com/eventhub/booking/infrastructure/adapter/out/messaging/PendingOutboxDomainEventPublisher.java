package com.eventhub.booking.infrastructure.adapter.out.messaging;

import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.domain.DomainEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY. Stands in for the transactional outbox until the messaging milestone (B6a): the events
 * the use cases produce are NOT stored or delivered yet. It exists so the web layer can be built
 * and tested end to end now; the {@code outbox_event} table is already in the schema. It logs only
 * how many events there were, never their content.
 */
@Component
class PendingOutboxDomainEventPublisher implements DomainEventPublisher {

  private static final Logger log =
      LoggerFactory.getLogger(PendingOutboxDomainEventPublisher.class);

  @Override
  public void publish(List<DomainEvent> events) {
    if (!events.isEmpty()) {
      log.debug(
          "{} domain event(s) not delivered: the outbox is not implemented yet", events.size());
    }
  }
}
