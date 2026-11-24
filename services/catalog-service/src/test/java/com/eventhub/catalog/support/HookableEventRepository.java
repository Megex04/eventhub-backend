package com.eventhub.catalog.support;

import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.domain.Event;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wraps the real adapter so a test can run code right after an event is loaded and before the use
 * case saves it, which is exactly the window in which a concurrent transaction makes a write stale.
 * Without a hook it behaves like the adapter it wraps.
 */
public final class HookableEventRepository implements EventRepository {

  private final EventRepository delegate;
  private volatile Consumer<UUID> afterLoad = id -> {};

  HookableEventRepository(EventRepository delegate) {
    this.delegate = delegate;
  }

  /** Runs once per load until {@link #reset()}; the hook decides whether to act again. */
  public void onAfterLoad(Consumer<UUID> hook) {
    this.afterLoad = hook;
  }

  public void reset() {
    this.afterLoad = id -> {};
  }

  @Override
  public Optional<Event> findById(UUID id) {
    Optional<Event> loaded = delegate.findById(id);
    afterLoad.accept(id);
    return loaded;
  }

  @Override
  public void save(Event event) {
    delegate.save(event);
  }

  @Override
  public void delete(Event event) {
    delegate.delete(event);
  }

  @Override
  public PageResult<EventSummary> search(
      EventSearchCriteria criteria, EventSort sort, PageRequest page) {
    return delegate.search(criteria, sort, page);
  }
}
