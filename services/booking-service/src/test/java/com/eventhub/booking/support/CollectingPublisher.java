package com.eventhub.booking.support;

import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.domain.DomainEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A thread-safe sink for the events of the integration tests (the outbox arrives later). */
public final class CollectingPublisher implements DomainEventPublisher {

  private final List<DomainEvent> events = new CopyOnWriteArrayList<>();

  @Override
  public void publish(List<DomainEvent> toPublish) {
    events.addAll(toPublish);
  }

  public long count(Class<? extends DomainEvent> type) {
    return events.stream().filter(type::isInstance).count();
  }

  public List<DomainEvent> all() {
    return List.copyOf(events);
  }
}
