package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;

public interface ListEventsUseCase {

  PageResult<EventSummary> list(Query query);

  record Query(EventSearchCriteria criteria, EventSort sort, PageRequest page) {}
}
