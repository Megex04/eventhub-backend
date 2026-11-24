package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import java.util.UUID;

public interface GetEventUseCase {

  /** Events that are not PUBLISHED are only visible to their organizer; others get "not found". */
  Event get(UUID eventId, Viewer viewer);
}
