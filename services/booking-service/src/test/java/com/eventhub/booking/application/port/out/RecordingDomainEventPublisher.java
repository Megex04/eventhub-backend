package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.DomainEvent;
import java.util.List;

/** Keeps what was published; events of a rolled-back transaction disappear with it. */
public final class RecordingDomainEventPublisher implements DomainEventPublisher {

  private final InMemoryStore store;

  public RecordingDomainEventPublisher(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public void publish(List<DomainEvent> events) {
    store.transaction(() -> {
      store.published.addAll(events);
      return null;
    });
  }

  public List<DomainEvent> events() {
    return store.transaction(() -> List.copyOf(store.published));
  }

  public <T extends DomainEvent> List<T> eventsOfType(Class<T> type) {
    return events().stream().filter(type::isInstance).map(type::cast).toList();
  }
}
