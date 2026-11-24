package com.eventhub.catalog.application.query;

import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.InvalidValueException;
import java.time.Instant;
import java.util.Optional;

/**
 * Filters of the listing. Visibility is always applied on top of them: a viewer sees PUBLISHED
 * events plus, when authenticated, their own events in any status.
 */
public record EventSearchCriteria(
    Viewer viewer,
    Optional<EventStatus> status,
    Optional<String> text,
    Optional<Instant> from,
    Optional<Instant> to,
    boolean onlyMine) {

  public static final int MAX_TEXT_LENGTH = 100;

  public EventSearchCriteria {
    text = text.map(String::strip).filter(value -> !value.isEmpty());
    if (text.isPresent() && text.get().length() > MAX_TEXT_LENGTH) {
      throw new InvalidValueException(
          "q", "q must have at most %d characters".formatted(MAX_TEXT_LENGTH));
    }
  }

  public static EventSearchCriteria visibleTo(Viewer viewer) {
    return new EventSearchCriteria(
        viewer, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
  }

  public EventSearchCriteria withStatus(EventStatus value) {
    return new EventSearchCriteria(viewer, Optional.of(value), text, from, to, onlyMine);
  }

  public EventSearchCriteria withText(String value) {
    return new EventSearchCriteria(viewer, status, Optional.of(value), from, to, onlyMine);
  }

  public EventSearchCriteria withFrom(Instant value) {
    return new EventSearchCriteria(viewer, status, text, Optional.of(value), to, onlyMine);
  }

  public EventSearchCriteria withTo(Instant value) {
    return new EventSearchCriteria(viewer, status, text, from, Optional.of(value), onlyMine);
  }

  public EventSearchCriteria mineOnly() {
    return new EventSearchCriteria(viewer, status, text, from, to, true);
  }
}
