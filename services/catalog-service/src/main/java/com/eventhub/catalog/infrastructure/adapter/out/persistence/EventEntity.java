package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import com.eventhub.catalog.domain.EventStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.OptimisticLock;

/**
 * Persistence shape of the Event aggregate; the domain model never sees this class.
 *
 * <p>The version is advanced exactly once per save by {@link JpaEventRepository}, which claims the
 * row before it writes anything (see {@code claimRow}). The mutable columns are therefore excluded
 * from Hibernate's own dirty-driven increment: otherwise a save would bump the version twice.
 */
@Entity
@Table(name = "event")
class EventEntity {

  @Id private UUID id;

  @Column(name = "organizer_id", nullable = false)
  private UUID organizerId;

  @OptimisticLock(excluded = true)
  @Column(nullable = false, length = 120)
  private String title;

  @OptimisticLock(excluded = true)
  @Column(nullable = false, length = 4000)
  private String description;

  @OptimisticLock(excluded = true)
  @Column(nullable = false, length = 160)
  private String venue;

  @OptimisticLock(excluded = true)
  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @OptimisticLock(excluded = true)
  @Column(name = "ends_at", nullable = false)
  private Instant endsAt;

  @OptimisticLock(excluded = true)
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EventStatus status;

  @OptimisticLock(excluded = true)
  @Column(name = "cancel_reason", length = 500)
  private String cancelReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @OptimisticLock(excluded = true)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<TicketZoneEntity> zones = new ArrayList<>();

  protected EventEntity() {}

  EventEntity(UUID id, UUID organizerId, Instant createdAt) {
    this.id = id;
    this.organizerId = organizerId;
    this.createdAt = createdAt;
  }

  UUID getId() {
    return id;
  }

  UUID getOrganizerId() {
    return organizerId;
  }

  String getTitle() {
    return title;
  }

  String getDescription() {
    return description;
  }

  String getVenue() {
    return venue;
  }

  Instant getStartsAt() {
    return startsAt;
  }

  Instant getEndsAt() {
    return endsAt;
  }

  EventStatus getStatus() {
    return status;
  }

  String getCancelReason() {
    return cancelReason;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  long getVersion() {
    return version;
  }

  List<TicketZoneEntity> getZones() {
    return zones;
  }

  void applyState(
      String title,
      String description,
      String venue,
      Instant startsAt,
      Instant endsAt,
      EventStatus status,
      String cancelReason,
      Instant updatedAt) {
    this.title = title;
    this.description = description;
    this.venue = venue;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.status = status;
    this.cancelReason = cancelReason;
    this.updatedAt = updatedAt;
  }
}
