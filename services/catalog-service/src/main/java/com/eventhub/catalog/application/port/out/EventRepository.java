package com.eventhub.catalog.application.port.out;

import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.domain.Event;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository {

  Optional<Event> findById(UUID id);

  /** Inserts or updates the aggregate, zones included. */
  void save(Event event);

  void delete(UUID id);

  /** Applies the criteria's filters AND its viewer-based visibility; never loads zones. */
  PageResult<EventSummary> search(EventSearchCriteria criteria, EventSort sort, PageRequest page);
}
