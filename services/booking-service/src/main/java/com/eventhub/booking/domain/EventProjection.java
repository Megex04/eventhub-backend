package com.eventhub.booking.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Local copy of the catalog facts that bookings depend on, fed by catalog events that may arrive
 * duplicated, late, out of order or replayed. It is monotonic: a message is applied only when its
 * {@code occurredAt} is not older than the last one applied (equal timestamps are accepted, so a
 * replay of the same state is harmless), and CANCELLED is terminal.
 *
 * <p>A cancellation can arrive before the publication it cancels. It is kept as a
 * <em>tombstone</em> (CANCELLED, no dates, no zones) so that the publication, when it comes, is
 * ignored.
 */
public final class EventProjection {

  /** What happened to an incoming message. */
  public enum ApplyResult {
    APPLIED,
    IGNORED_STALE,
    IGNORED_CANCELLED
  }

  private final UUID eventId;
  private EventSaleStatus status;
  // Absent only in a tombstone, which never had a schedule.
  private Instant startsAt;
  private Instant endsAt;
  private List<ProjectedZone> zones;
  private Instant lastOccurredAt;

  private EventProjection(
      UUID eventId,
      EventSaleStatus status,
      Instant startsAt,
      Instant endsAt,
      List<ProjectedZone> zones,
      Instant lastOccurredAt) {
    this.eventId = eventId;
    this.status = status;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.zones = List.copyOf(zones);
    this.lastOccurredAt = lastOccurredAt;
  }

  public static EventProjection published(
      UUID eventId,
      Instant startsAt,
      Instant endsAt,
      List<ProjectedZone> zones,
      Instant occurredAt) {
    Validation.requiredId("eventId", eventId);
    requirePeriod(startsAt, endsAt);
    requireZones(zones);
    Validation.required("occurredAt", occurredAt);
    return new EventProjection(eventId, EventSaleStatus.OPEN, startsAt, endsAt, zones, occurredAt);
  }

  /** A cancellation received before the event was ever published here. */
  public static EventProjection cancelledTombstone(UUID eventId, Instant occurredAt) {
    Validation.requiredId("eventId", eventId);
    Validation.required("occurredAt", occurredAt);
    return new EventProjection(
        eventId, EventSaleStatus.CANCELLED, null, null, List.of(), occurredAt);
  }

  /**
   * Rebuilds the projection from persisted state without re-validating. {@code startsAt} and {@code
   * endsAt} are null only for a tombstone.
   */
  public static EventProjection restore(
      UUID eventId,
      EventSaleStatus status,
      Instant startsAt,
      Instant endsAt,
      List<ProjectedZone> zones,
      Instant lastOccurredAt) {
    return new EventProjection(eventId, status, startsAt, endsAt, zones, lastOccurredAt);
  }

  /** Also the entry point of a replay: it refreshes the data of an event that is still open. */
  public ApplyResult applyPublished(
      Instant newStartsAt, Instant newEndsAt, List<ProjectedZone> newZones, Instant occurredAt) {
    requirePeriod(newStartsAt, newEndsAt);
    requireZones(newZones);
    return applyIfCurrent(
        occurredAt,
        () -> {
          this.startsAt = newStartsAt;
          this.endsAt = newEndsAt;
          this.zones = List.copyOf(newZones);
        });
  }

  public ApplyResult applyRescheduled(Instant newStartsAt, Instant newEndsAt, Instant occurredAt) {
    requirePeriod(newStartsAt, newEndsAt);
    return applyIfCurrent(
        occurredAt,
        () -> {
          this.startsAt = newStartsAt;
          this.endsAt = newEndsAt;
        });
  }

  public ApplyResult applyCancelled(Instant occurredAt) {
    return applyIfCurrent(occurredAt, () -> this.status = EventSaleStatus.CANCELLED);
  }

  /** True while the event is open and has not started yet. */
  public boolean isOnSale(Clock clock) {
    return status == EventSaleStatus.OPEN && clock.instant().isBefore(startsAt);
  }

  /** True when only the cancellation is known: there is nothing to show or to sell. */
  public boolean isTombstone() {
    return zones.isEmpty();
  }

  public Optional<ProjectedZone> zone(UUID zoneId) {
    return zones.stream().filter(zone -> zone.zoneId().equals(zoneId)).findFirst();
  }

  public UUID eventId() {
    return eventId;
  }

  public EventSaleStatus status() {
    return status;
  }

  public Optional<Instant> startsAt() {
    return Optional.ofNullable(startsAt);
  }

  public Optional<Instant> endsAt() {
    return Optional.ofNullable(endsAt);
  }

  public List<ProjectedZone> zones() {
    return zones;
  }

  public Instant lastOccurredAt() {
    return lastOccurredAt;
  }

  private ApplyResult applyIfCurrent(Instant occurredAt, Runnable change) {
    Validation.required("occurredAt", occurredAt);
    if (status == EventSaleStatus.CANCELLED) {
      return ApplyResult.IGNORED_CANCELLED;
    }
    if (occurredAt.isBefore(lastOccurredAt)) {
      return ApplyResult.IGNORED_STALE;
    }
    change.run();
    lastOccurredAt = occurredAt;
    return ApplyResult.APPLIED;
  }

  private static void requirePeriod(Instant startsAt, Instant endsAt) {
    Validation.required("startsAt", startsAt);
    Validation.required("endsAt", endsAt);
    if (!endsAt.isAfter(startsAt)) {
      throw new InvalidValueException("endsAt", "endsAt must be after startsAt");
    }
  }

  private static void requireZones(List<ProjectedZone> zones) {
    Validation.required("zones", zones);
    if (zones.isEmpty()) {
      throw new InvalidValueException("zones", "zones must not be empty");
    }
  }
}
