package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.in.ApplyEventPublishedUseCase;
import com.eventhub.booking.application.port.in.EventPublishedMessage;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.EventProjection.ApplyResult;
import com.eventhub.booking.domain.ProjectedZone;
import java.util.Optional;

/**
 * Applies {@code EventPublished} (also a replay of it) in one transaction. Idempotent at two
 * levels: the message id is recorded so a redelivery is skipped, and the projection and the zone
 * capacities are upserted by business key, so a different message with the same content changes
 * nothing. Upserting a zone never touches {@code reserved}.
 */
public class ApplyEventPublishedService implements ApplyEventPublishedUseCase {

  private final TransactionRunner transactions;
  private final EventProjectionRepository projections;
  private final InventoryRepository inventory;
  private final ProcessedMessageRepository processed;

  public ApplyEventPublishedService(
      TransactionRunner transactions,
      EventProjectionRepository projections,
      InventoryRepository inventory,
      ProcessedMessageRepository processed) {
    this.transactions = transactions;
    this.projections = projections;
    this.inventory = inventory;
    this.processed = processed;
  }

  @Override
  public void apply(EventPublishedMessage message) {
    transactions.run(() -> applyOnce(message));
  }

  private void applyOnce(EventPublishedMessage message) {
    if (!processed.markProcessed(message.messageId())) {
      return;
    }
    Optional<EventProjection> existing = projections.findById(message.eventId());
    if (existing.isEmpty()) {
      projections.save(
          EventProjection.published(
              message.eventId(),
              message.startsAt(),
              message.endsAt(),
              message.zones(),
              message.occurredAt()));
      openInventory(message);
      return;
    }
    EventProjection projection = existing.get();
    ApplyResult result =
        projection.applyPublished(
            message.startsAt(), message.endsAt(), message.zones(), message.occurredAt());
    if (result == ApplyResult.APPLIED) {
      projections.save(projection);
      openInventory(message);
    }
  }

  private void openInventory(EventPublishedMessage message) {
    for (ProjectedZone zone : message.zones()) {
      inventory.upsertCapacity(message.eventId(), zone.zoneId(), zone.capacity());
    }
  }
}
