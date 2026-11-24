package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.exception.EventAccessDeniedException;
import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import java.util.UUID;

/** Loads events applying the visibility and ownership rules shared by all use cases. */
final class EventLookup {

  private final EventRepository repository;

  EventLookup(EventRepository repository) {
    this.repository = repository;
  }

  /** Visible events only: non-PUBLISHED ones exist solely for their organizer. */
  Event visibleTo(UUID eventId, Viewer viewer) {
    Event event =
        repository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    if (!isVisible(event, viewer)) {
      throw new EventNotFoundException(eventId);
    }
    return event;
  }

  /**
   * Hidden events answer "not found" (404, no existence leak); visible ones owned by someone else
   * answer "denied" (403).
   */
  Event ownedBy(UUID eventId, UUID requesterId) {
    Viewer requester = Viewer.authenticated(requesterId);
    Event event = visibleTo(eventId, requester);
    if (!event.isOrganizedBy(requesterId)) {
      throw new EventAccessDeniedException(eventId);
    }
    return event;
  }

  private static boolean isVisible(Event event, Viewer viewer) {
    return event.status() == EventStatus.PUBLISHED || viewer.isOrganizerOf(event);
  }
}
