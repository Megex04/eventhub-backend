package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.InventoryInvariantException;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.domain.ZoneInventory;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class InMemoryInventoryRepository implements InventoryRepository {

  private final InMemoryStore store;

  public InMemoryInventoryRepository(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public void reserve(UUID eventId, List<RequestedItem> items) {
    store.transaction(() -> {
      for (RequestedItem item : inZoneOrder(items)) {
        var key = new InMemoryStore.ZoneKey(eventId, item.zoneId());
        ZoneInventory zone = store.inventories.get(key);
        if (zone == null) {
          throw new InsufficientInventoryException(item.zoneId(), item.quantity(), 0);
        }
        ZoneInventory updated = InMemoryStore.copy(zone);
        updated.reserve(item.quantity());
        store.inventories.put(key, updated);
      }
      return null;
    });
  }

  @Override
  public void release(UUID eventId, List<RequestedItem> items) {
    store.transaction(() -> {
      for (RequestedItem item : inZoneOrder(items)) {
        var key = new InMemoryStore.ZoneKey(eventId, item.zoneId());
        ZoneInventory zone = store.inventories.get(key);
        if (zone == null) {
          throw new InventoryInvariantException(item.zoneId(), 0, item.quantity());
        }
        ZoneInventory updated = InMemoryStore.copy(zone);
        updated.release(item.quantity());
        store.inventories.put(key, updated);
      }
      return null;
    });
  }

  @Override
  public void upsertCapacity(UUID eventId, UUID zoneId, int capacity) {
    store.transaction(() -> {
      var key = new InMemoryStore.ZoneKey(eventId, zoneId);
      ZoneInventory current = store.inventories.get(key);
      store.inventories.put(
          key,
          current == null
              ? ZoneInventory.create(eventId, zoneId, capacity)
              : ZoneInventory.restore(
                  eventId, zoneId, capacity, current.reserved(), current.version()));
      return null;
    });
  }

  @Override
  public List<ZoneInventory> findByEvent(UUID eventId) {
    return store.transaction(() ->
        store.inventories.values().stream()
            .filter(zone -> zone.eventId().equals(eventId))
            .sorted(Comparator.comparing(ZoneInventory::zoneId))
            .map(InMemoryStore::copy)
            .toList());
  }

  private static List<RequestedItem> inZoneOrder(List<RequestedItem> items) {
    return items.stream().sorted(Comparator.comparing(RequestedItem::zoneId)).toList();
  }
}
