package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.EventProjection;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryEventProjectionRepository implements EventProjectionRepository {

  private final InMemoryStore store;

  public InMemoryEventProjectionRepository(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public Optional<EventProjection> findById(UUID eventId) {
    return store.transaction(
        () -> Optional.ofNullable(store.projections.get(eventId)).map(InMemoryStore::copy));
  }

  @Override
  public void save(EventProjection projection) {
    store.transaction(() -> {
      store.projections.put(projection.eventId(), InMemoryStore.copy(projection));
      return null;
    });
  }
}
