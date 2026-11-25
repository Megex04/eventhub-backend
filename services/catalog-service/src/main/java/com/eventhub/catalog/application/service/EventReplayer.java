package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.domain.DomainEvent;
import com.eventhub.catalog.domain.Event;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays ONE event in its own transaction, so a conflict on one event never undoes the others.
 *
 * <p>The replay is a write on the aggregate: saving it claims the event row and advances its
 * version (ADR 0004), so a concurrent change either happens before (and is replayed) or fails with
 * a conflict. That keeps the outbox in the order the consumers must see.
 */
@Service
@Transactional
public class EventReplayer {

  private final EventRepository repository;
  private final DomainEventPublisher publisher;

  public EventReplayer(EventRepository repository, DomainEventPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  /**
   * @return outbox rows written; 0 when the event is not replayable (a draft)
   * @throws EventNotFoundException if there is no such event
   * @throws com.eventhub.catalog.application.exception.EventVersionConflictException if it changed
   */
  public int replay(UUID eventId) {
    Event event =
        repository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    if (!event.isReplayable()) {
      return 0;
    }
    List<DomainEvent> events = event.recordReplay();
    repository.save(event);
    publisher.publish(events);
    return events.size();
  }
}
