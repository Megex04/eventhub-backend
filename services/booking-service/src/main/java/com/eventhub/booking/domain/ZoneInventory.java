package com.eventhub.booking.domain;

import java.util.UUID;

/**
 * Tickets of one zone of one event: {@code capacity} comes from the catalog, {@code reserved} is
 * owned here. Invariant: {@code 0 <= reserved <= capacity}.
 *
 * <p>In production the reservation is a conditional SQL {@code UPDATE} (never read-modify-write);
 * this type states the same rule for the in-memory fakes and the unit tests. The version belongs to
 * persistence and is read-only here.
 */
public final class ZoneInventory {

  private final UUID eventId;
  private final UUID zoneId;
  private final int capacity;
  private int reserved;
  private final long version;

  private ZoneInventory(UUID eventId, UUID zoneId, int capacity, int reserved, long version) {
    this.eventId = eventId;
    this.zoneId = zoneId;
    this.capacity = capacity;
    this.reserved = reserved;
    this.version = version;
  }

  public static ZoneInventory create(UUID eventId, UUID zoneId, int capacity) {
    Validation.requiredId("eventId", eventId);
    Validation.requiredId("zoneId", zoneId);
    Validation.atLeast("capacity", capacity, 1);
    return new ZoneInventory(eventId, zoneId, capacity, 0, 0L);
  }

  /** Rebuilds the inventory from persisted state without re-validating. */
  public static ZoneInventory restore(
      UUID eventId, UUID zoneId, int capacity, int reserved, long version) {
    return new ZoneInventory(eventId, zoneId, capacity, reserved, version);
  }

  public void reserve(int quantity) {
    Validation.atLeast("quantity", quantity, 1);
    if (quantity > available()) {
      throw new InsufficientInventoryException(zoneId, quantity, available());
    }
    reserved += quantity;
  }

  public void release(int quantity) {
    Validation.atLeast("quantity", quantity, 1);
    if (quantity > reserved) {
      throw new InventoryInvariantException(zoneId, reserved, quantity);
    }
    reserved -= quantity;
  }

  public int available() {
    return capacity - reserved;
  }

  public UUID eventId() {
    return eventId;
  }

  public UUID zoneId() {
    return zoneId;
  }

  public int capacity() {
    return capacity;
  }

  public int reserved() {
    return reserved;
  }

  public long version() {
    return version;
  }
}
