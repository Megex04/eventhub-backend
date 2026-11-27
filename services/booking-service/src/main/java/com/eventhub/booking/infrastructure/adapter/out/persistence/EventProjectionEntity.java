package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.domain.EventSaleStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Projection of a catalog event. {@code startsAt} and {@code endsAt} are null only in a tombstone.
 * The version makes two consumers that apply messages for the same event at once conflict instead
 * of overwriting each other: the loser fails and its message is redelivered.
 */
@Entity
@Table(name = "event_projection")
class EventProjectionEntity {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EventSaleStatus status;

  @Column(name = "starts_at")
  private Instant startsAt;

  @Column(name = "ends_at")
  private Instant endsAt;

  @Column(name = "last_occurred_at", nullable = false)
  private Instant lastOccurredAt;

  @Version private long version;

  @ElementCollection
  @CollectionTable(name = "projected_zone", joinColumns = @JoinColumn(name = "event_id"))
  @OrderBy("position ASC")
  private List<ProjectedZoneEmbeddable> zones = new ArrayList<>();

  protected EventProjectionEntity() {}

  EventProjectionEntity(UUID eventId) {
    this.eventId = eventId;
  }

  void applyState(
      EventSaleStatus status,
      Instant startsAt,
      Instant endsAt,
      Instant lastOccurredAt,
      List<ProjectedZoneEmbeddable> newZones) {
    this.status = status;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.lastOccurredAt = lastOccurredAt;
    if (!sameZones(newZones)) {
      zones.clear();
      zones.addAll(newZones);
    }
  }

  private boolean sameZones(List<ProjectedZoneEmbeddable> other) {
    if (zones.size() != other.size()) {
      return false;
    }
    for (int i = 0; i < zones.size(); i++) {
      if (!zones.get(i).sameAs(other.get(i))) {
        return false;
      }
    }
    return true;
  }

  UUID getEventId() {
    return eventId;
  }

  EventSaleStatus getStatus() {
    return status;
  }

  Instant getStartsAt() {
    return startsAt;
  }

  Instant getEndsAt() {
    return endsAt;
  }

  Instant getLastOccurredAt() {
    return lastOccurredAt;
  }

  long getVersion() {
    return version;
  }

  List<ProjectedZoneEmbeddable> getZones() {
    return zones;
  }
}
