package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.exception.AuthenticationRequiredException;
import com.eventhub.catalog.application.port.in.GetEventUseCase;
import com.eventhub.catalog.application.port.in.ListEventsUseCase;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventQueryService implements GetEventUseCase, ListEventsUseCase {

  private final EventRepository repository;
  private final EventLookup lookup;

  public EventQueryService(EventRepository repository) {
    this.repository = repository;
    this.lookup = new EventLookup(repository);
  }

  @Override
  public Event get(UUID eventId, Viewer viewer) {
    return lookup.visibleTo(eventId, viewer);
  }

  @Override
  public PageResult<EventSummary> list(ListEventsUseCase.Query query) {
    EventSearchCriteria criteria = query.criteria();
    if (criteria.onlyMine() && !criteria.viewer().isAuthenticated()) {
      throw new AuthenticationRequiredException("Listing your own events requires authentication");
    }
    return repository.search(criteria, query.sort(), query.page());
  }
}
