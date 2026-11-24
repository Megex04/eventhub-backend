package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.domain.Event;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Fake of the persistence port. Filtering semantics are verified against real Postgres in M4. */
final class InMemoryEventRepository implements EventRepository {

  private final Map<UUID, Event> store = new LinkedHashMap<>();
  private PageResult<EventSummary> searchResult = new PageResult<>(List.of(), 0, 20, 0);

  EventSearchCriteria lastCriteria;
  EventSort lastSort;
  PageRequest lastPage;
  int searchCount;

  @Override
  public Optional<Event> findById(UUID id) {
    return Optional.ofNullable(store.get(id));
  }

  @Override
  public void save(Event event) {
    store.put(event.id(), event);
  }

  @Override
  public void delete(UUID id) {
    store.remove(id);
  }

  @Override
  public PageResult<EventSummary> search(
      EventSearchCriteria criteria, EventSort sort, PageRequest page) {
    this.lastCriteria = criteria;
    this.lastSort = sort;
    this.lastPage = page;
    this.searchCount++;
    return searchResult;
  }

  void willReturn(PageResult<EventSummary> result) {
    this.searchResult = result;
  }

  boolean contains(UUID id) {
    return store.containsKey(id);
  }

  int size() {
    return store.size();
  }
}
