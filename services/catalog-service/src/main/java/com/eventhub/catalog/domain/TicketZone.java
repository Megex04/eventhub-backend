package com.eventhub.catalog.domain;

import java.util.UUID;

/**
 * A priced block of tickets inside an event. Immutable; changes produce a copy with the same id.
 */
public final class TicketZone {

  static final int MAX_NAME_LENGTH = 60;
  static final int MAX_CAPACITY = 100_000;

  private final UUID id;
  private final String name;
  private final Money price;
  private final int capacity;

  private TicketZone(UUID id, String name, Money price, int capacity) {
    this.id = id;
    this.name = name;
    this.price = price;
    this.capacity = capacity;
  }

  public static TicketZone create(UUID id, String name, Money price, int capacity) {
    Validation.requiredId("id", id);
    String validName = Validation.text("name", name, 1, MAX_NAME_LENGTH);
    Validation.required("price", price);
    if (capacity < 1 || capacity > MAX_CAPACITY) {
      throw new InvalidValueException(
          "capacity", "capacity must be between 1 and %d".formatted(MAX_CAPACITY));
    }
    return new TicketZone(id, validName, price, capacity);
  }

  public TicketZone withDetails(String newName, Money newPrice, int newCapacity) {
    return create(id, newName, newPrice, newCapacity);
  }

  /** Zone names are unique per event ignoring case and surrounding whitespace. */
  boolean hasName(String candidate) {
    return name.equalsIgnoreCase(candidate.strip());
  }

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public Money price() {
    return price;
  }

  public int capacity() {
    return capacity;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TicketZone zone && id.equals(zone.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "TicketZone[id=%s, name=%s]".formatted(id, name);
  }
}
