package com.eventhub.catalog.application.port.out;

import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * In-memory fake of the persistence port. It passes the same {@link EventRepositoryContract} as the
 * JPA adapter: it hands out copies (never the stored instance), tracks a version per event and
 * rejects stale writes, and implements filtering, visibility, ordering and paging.
 */
public final class InMemoryEventRepository implements EventRepository {

  private final Map<UUID, Event> store = new LinkedHashMap<>();

  private EventSearchCriteria lastCriteria;
  private EventSort lastSort;
  private PageRequest lastPage;
  private int searchCount;

  @Override
  public Optional<Event> findById(UUID id) {
    return Optional.ofNullable(store.get(id)).map(stored -> copy(stored, stored.version()));
  }

  @Override
  public void save(Event event) {
    Event stored = store.get(event.id());
    if (stored == null) {
      store.put(event.id(), copy(event, 0L));
      return;
    }
    requireCurrent(event, stored);
    store.put(event.id(), copy(event, stored.version() + 1));
  }

  @Override
  public void delete(Event event) {
    Event stored = store.get(event.id());
    if (stored == null) {
      return;
    }
    requireCurrent(event, stored);
    store.remove(event.id());
  }

  @Override
  public PageResult<EventSummary> search(
      EventSearchCriteria criteria, EventSort sort, PageRequest page) {
    this.lastCriteria = criteria;
    this.lastSort = sort;
    this.lastPage = page;
    this.searchCount++;
    List<Event> matches =
        store.values().stream().filter(matching(criteria)).sorted(comparator(sort)).toList();
    int from = (int) Math.min((long) page.page() * page.size(), matches.size());
    int to = Math.min(from + page.size(), matches.size());
    List<EventSummary> content = matches.subList(from, to).stream().map(this::summary).toList();
    return new PageResult<>(content, page.page(), page.size(), matches.size());
  }

  public boolean contains(UUID id) {
    return store.containsKey(id);
  }

  public int size() {
    return store.size();
  }

  public EventSearchCriteria lastCriteria() {
    return lastCriteria;
  }

  public EventSort lastSort() {
    return lastSort;
  }

  public PageRequest lastPage() {
    return lastPage;
  }

  public int searchCount() {
    return searchCount;
  }

  private static void requireCurrent(Event event, Event stored) {
    if (event.version() != stored.version()) {
      throw new EventVersionConflictException(event.id());
    }
  }

  private static Event copy(Event event, long version) {
    return Event.restore(
        event.id(),
        event.organizerId(),
        event.title(),
        event.description(),
        event.venue(),
        event.startsAt(),
        event.endsAt(),
        event.status(),
        event.cancelReason(),
        event.zones(),
        event.createdAt(),
        event.updatedAt(),
        version);
  }

  private static Predicate<Event> matching(EventSearchCriteria criteria) {
    return event ->
        isVisible(event, criteria)
            && (!criteria.onlyMine() || criteria.viewer().isOrganizerOf(event))
            && criteria.status().map(status -> event.status() == status).orElse(true)
            && criteria.text().map(text -> containsText(event, text)).orElse(true)
            && criteria.from().map(from -> !event.startsAt().isBefore(from)).orElse(true)
            && criteria.to().map(to -> !event.startsAt().isAfter(to)).orElse(true);
  }

  private static boolean isVisible(Event event, EventSearchCriteria criteria) {
    return event.status() == EventStatus.PUBLISHED || criteria.viewer().isOrganizerOf(event);
  }

  private static boolean containsText(Event event, String text) {
    String needle = text.toLowerCase(Locale.ROOT);
    return event.title().toLowerCase(Locale.ROOT).contains(needle)
        || event.venue().toLowerCase(Locale.ROOT).contains(needle);
  }

  private static Comparator<Event> comparator(EventSort sort) {
    Comparator<Event> byTitle = Comparator.comparing(Event::title, String.CASE_INSENSITIVE_ORDER);
    Comparator<Event> byStart = Comparator.comparing(Event::startsAt);
    Comparator<Event> primary =
        switch (sort) {
          case STARTS_AT_ASC -> byStart;
          case STARTS_AT_DESC -> byStart.reversed();
          case TITLE_ASC -> byTitle;
          case TITLE_DESC -> byTitle.reversed();
        };
    return primary.thenComparing(Event::id);
  }

  private EventSummary summary(Event event) {
    return new EventSummary(
        event.id(), event.title(), event.venue(), event.startsAt(), event.endsAt(), event.status());
  }
}
