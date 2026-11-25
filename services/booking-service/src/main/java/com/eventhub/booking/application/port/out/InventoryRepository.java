package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.domain.ZoneInventory;
import java.util.List;
import java.util.UUID;

/**
 * Tickets per zone. {@code reserve} is one atomic conditional update per zone, in zoneId order, all
 * or nothing: it never reads and then writes. Releasing is done only on the state transition that
 * justifies it.
 */
public interface InventoryRepository {

  /** Throws {@code InsufficientInventoryException} (and reserves nothing) if any zone is short. */
  void reserve(UUID eventId, List<RequestedItem> items);

  /** Throws {@code InventoryInvariantException} if it would leave a zone below zero. */
  void release(UUID eventId, List<RequestedItem> items);

  /** Creates the zone or updates its capacity; {@code reserved} is never touched. */
  void upsertCapacity(UUID eventId, UUID zoneId, int capacity);

  List<ZoneInventory> findByEvent(UUID eventId);
}
