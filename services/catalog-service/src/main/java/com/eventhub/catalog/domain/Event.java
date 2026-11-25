package com.eventhub.catalog.domain;

import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root. Checks run in a fixed order so callers get a predictable error: lifecycle state
 * (409), then field values (400), then business rules (422).
 */
public final class Event {

  public static final int MAX_ZONES = 20;
  private static final int MIN_TITLE = 3;
  private static final int MAX_TITLE = 120;
  private static final int MIN_VENUE = 2;
  private static final int MAX_VENUE = 160;
  private static final int MAX_DESCRIPTION = 4000;
  private static final int MIN_REASON = 3;
  private static final int MAX_REASON = 500;

  private final UUID id;
  private final UUID organizerId;
  private String title;
  private String description;
  private String venue;
  private Instant startsAt;
  private Instant endsAt;
  private EventStatus status;
  private String cancelReason;
  private final List<TicketZone> zones;
  private final Instant createdAt;
  private Instant updatedAt;
  // Optimistic-locking token owned by persistence: read-only here, never changed by the domain.
  private final long version;
  private final List<DomainEvent> pendingEvents = new ArrayList<>();

  private Event(
      UUID id,
      UUID organizerId,
      String title,
      String description,
      String venue,
      Instant startsAt,
      Instant endsAt,
      EventStatus status,
      String cancelReason,
      List<TicketZone> zones,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    this.id = id;
    this.organizerId = organizerId;
    this.title = title;
    this.description = description;
    this.venue = venue;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.status = status;
    this.cancelReason = cancelReason;
    this.zones = new ArrayList<>(zones);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  /** Creates a DRAFT. A past {@code startsAt} is accepted here; it only blocks publication. */
  public static Event create(
      UUID id,
      UUID organizerId,
      String title,
      String description,
      String venue,
      Instant startsAt,
      Instant endsAt,
      List<TicketZone> initialZones,
      Clock clock) {
    Validation.requiredId("id", id);
    Validation.requiredId("organizerId", organizerId);
    String validTitle = Validation.text("title", title, MIN_TITLE, MAX_TITLE);
    String validDescription = Validation.optionalText("description", description, MAX_DESCRIPTION);
    String validVenue = Validation.text("venue", venue, MIN_VENUE, MAX_VENUE);
    Validation.period(startsAt, endsAt);
    Instant now = clock.instant();
    Event event =
        new Event(
            id,
            organizerId,
            validTitle,
            validDescription,
            validVenue,
            startsAt,
            endsAt,
            EventStatus.DRAFT,
            null,
            List.of(),
            now,
            now,
            0L);
    initialZones.forEach(event::registerZone);
    return event;
  }

  /** Rebuilds an aggregate from persisted state without re-validating or recording events. */
  public static Event restore(
      UUID id,
      UUID organizerId,
      String title,
      String description,
      String venue,
      Instant startsAt,
      Instant endsAt,
      EventStatus status,
      Optional<String> cancelReason,
      List<TicketZone> zones,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    return new Event(
        Objects.requireNonNull(id),
        Objects.requireNonNull(organizerId),
        Objects.requireNonNull(title),
        Objects.requireNonNull(description),
        Objects.requireNonNull(venue),
        Objects.requireNonNull(startsAt),
        Objects.requireNonNull(endsAt),
        Objects.requireNonNull(status),
        cancelReason.orElse(null),
        zones,
        Objects.requireNonNull(createdAt),
        Objects.requireNonNull(updatedAt),
        version);
  }

  public void update(
      String newTitle,
      String newDescription,
      String newVenue,
      Instant newStartsAt,
      Instant newEndsAt,
      Clock clock) {
    requireStatus(EventStatus.DRAFT, "update");
    String validTitle = Validation.text("title", newTitle, MIN_TITLE, MAX_TITLE);
    String validDescription =
        Validation.optionalText("description", newDescription, MAX_DESCRIPTION);
    String validVenue = Validation.text("venue", newVenue, MIN_VENUE, MAX_VENUE);
    Validation.period(newStartsAt, newEndsAt);
    this.title = validTitle;
    this.description = validDescription;
    this.venue = validVenue;
    this.startsAt = newStartsAt;
    this.endsAt = newEndsAt;
    touch(clock);
  }

  public void addZone(TicketZone zone, Clock clock) {
    requireStatus(EventStatus.DRAFT, "add zones to");
    registerZone(zone);
    touch(clock);
  }

  public TicketZone updateZone(UUID zoneId, String name, Money price, int capacity, Clock clock) {
    requireStatus(EventStatus.DRAFT, "update zones of");
    int index = indexOfZone(zoneId);
    TicketZone updated = zones.get(index).withDetails(name, price, capacity);
    requireUniqueZoneName(updated.name(), zoneId);
    zones.set(index, updated);
    touch(clock);
    return updated;
  }

  public void removeZone(UUID zoneId, Clock clock) {
    requireStatus(EventStatus.DRAFT, "remove zones from");
    zones.remove(indexOfZone(zoneId));
    touch(clock);
  }

  /**
   * DRAFT -> PUBLISHED. The "endsAt after startsAt" rule needs no check here: the aggregate cannot
   * be built or modified into a state that violates it.
   */
  public void publish(Clock clock) {
    requireStatus(EventStatus.DRAFT, "publish");
    Instant now = clock.instant();
    if (zones.isEmpty()) {
      throw new BusinessRuleViolationException(
          Rule.EVENT_WITHOUT_ZONES, "An event needs at least one zone to be published");
    }
    requireFuture(startsAt, now);
    this.status = EventStatus.PUBLISHED;
    this.updatedAt = now;
    pendingEvents.add(publishedSnapshot(now));
  }

  /**
   * Describes the event as it is now, for a consumer that missed what happened before: PUBLISHED ->
   * {@code EventPublished}; CANCELLED -> {@code EventPublished} then {@code EventCancelled}. Both
   * carry the instant of the last change ({@code updatedAt}), because the original publication
   * instant is not stored. Nothing about the event changes. A DRAFT has not happened yet, so it is
   * never replayed.
   */
  public List<DomainEvent> recordReplay() {
    return switch (status) {
      case PUBLISHED -> List.of(publishedSnapshot(updatedAt));
      case CANCELLED ->
          List.of(publishedSnapshot(updatedAt), new EventCancelled(id, cancelReason, updatedAt));
      case DRAFT, FINISHED -> throw InvalidStateException.notAllowed("replay", status);
    };
  }

  /** Only an event that has been published (and possibly cancelled) has a history to describe. */
  public boolean isReplayable() {
    return status == EventStatus.PUBLISHED || status == EventStatus.CANCELLED;
  }

  private EventPublished publishedSnapshot(Instant occurredAt) {
    return new EventPublished(
        id,
        organizerId,
        title,
        venue,
        startsAt,
        endsAt,
        zones.stream()
            .map(z -> new EventPublished.Zone(z.id(), z.name(), z.capacity(), z.price()))
            .toList(),
        occurredAt);
  }

  /** PUBLISHED -> CANCELLED (terminal). Allowed even after the event has started. */
  public void cancel(String reason, Clock clock) {
    requireStatus(EventStatus.PUBLISHED, "cancel");
    String validReason = Validation.text("reason", reason, MIN_REASON, MAX_REASON);
    this.status = EventStatus.CANCELLED;
    this.cancelReason = validReason;
    touch(clock);
    pendingEvents.add(new EventCancelled(id, validReason, updatedAt));
  }

  public void reschedule(Instant newStartsAt, Instant newEndsAt, Clock clock) {
    reschedule(newStartsAt, newEndsAt, venue, clock);
  }

  public void reschedule(Instant newStartsAt, Instant newEndsAt, String newVenue, Clock clock) {
    requireStatus(EventStatus.PUBLISHED, "reschedule");
    Validation.period(newStartsAt, newEndsAt);
    String validVenue = Validation.text("venue", newVenue, MIN_VENUE, MAX_VENUE);
    Instant now = clock.instant();
    if (!startsAt.isAfter(now)) {
      throw new BusinessRuleViolationException(
          Rule.EVENT_ALREADY_STARTED, "An event that has already started cannot be rescheduled");
    }
    requireFuture(newStartsAt, now);
    this.startsAt = newStartsAt;
    this.endsAt = newEndsAt;
    this.venue = validVenue;
    this.updatedAt = now;
    pendingEvents.add(new EventRescheduled(id, newStartsAt, newEndsAt, validVenue, now));
  }

  /** Only drafts can be removed; the use case deletes the row after this guard passes. */
  public void ensureDeletable() {
    requireStatus(EventStatus.DRAFT, "delete");
  }

  public boolean isOrganizedBy(UUID userId) {
    return organizerId.equals(userId);
  }

  /** Returns the events recorded since the last call and forgets them. */
  public List<DomainEvent> pullDomainEvents() {
    List<DomainEvent> events = List.copyOf(pendingEvents);
    pendingEvents.clear();
    return events;
  }

  public UUID id() {
    return id;
  }

  public UUID organizerId() {
    return organizerId;
  }

  public String title() {
    return title;
  }

  public String description() {
    return description;
  }

  public String venue() {
    return venue;
  }

  public Instant startsAt() {
    return startsAt;
  }

  public Instant endsAt() {
    return endsAt;
  }

  public EventStatus status() {
    return status;
  }

  public Optional<String> cancelReason() {
    return Optional.ofNullable(cancelReason);
  }

  public List<TicketZone> zones() {
    return List.copyOf(zones);
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  /** Version this instance was loaded with (0 for a new event); used to detect stale writes. */
  public long version() {
    return version;
  }

  private void registerZone(TicketZone zone) {
    Validation.required("zone", zone);
    if (zones.size() >= MAX_ZONES) {
      throw new BusinessRuleViolationException(
          Rule.ZONE_LIMIT_EXCEEDED, "An event cannot have more than %d zones".formatted(MAX_ZONES));
    }
    requireUniqueZoneName(zone.name(), zone.id());
    zones.add(zone);
  }

  private void requireUniqueZoneName(String name, UUID ownerZoneId) {
    boolean taken = zones.stream().anyMatch(z -> !z.id().equals(ownerZoneId) && z.hasName(name));
    if (taken) {
      throw new BusinessRuleViolationException(
          Rule.DUPLICATE_ZONE_NAME, "Zone name '%s' is already used in this event".formatted(name));
    }
  }

  private int indexOfZone(UUID zoneId) {
    for (int i = 0; i < zones.size(); i++) {
      if (zones.get(i).id().equals(zoneId)) {
        return i;
      }
    }
    throw new ZoneNotFoundException(zoneId);
  }

  private void requireStatus(EventStatus expected, String action) {
    if (status != expected) {
      throw InvalidStateException.notAllowed(action, status);
    }
  }

  private static void requireFuture(Instant instant, Instant now) {
    if (!instant.isAfter(now)) {
      throw new BusinessRuleViolationException(
          Rule.STARTS_AT_NOT_IN_FUTURE, "startsAt must be in the future");
    }
  }

  private void touch(Clock clock) {
    this.updatedAt = clock.instant();
  }
}
