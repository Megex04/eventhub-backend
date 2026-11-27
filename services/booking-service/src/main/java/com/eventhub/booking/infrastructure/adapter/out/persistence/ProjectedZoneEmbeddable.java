package com.eventhub.booking.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Embeddable
class ProjectedZoneEmbeddable {

  @Column(name = "zone_id", nullable = false)
  private UUID zoneId;

  @Column(nullable = false)
  private int position;

  @Column(nullable = false)
  private String name;

  @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal priceAmount;

  @Column(name = "price_currency", nullable = false, length = 3)
  private String priceCurrency;

  @Column(nullable = false)
  private int capacity;

  protected ProjectedZoneEmbeddable() {}

  ProjectedZoneEmbeddable(
      UUID zoneId,
      int position,
      String name,
      BigDecimal priceAmount,
      String priceCurrency,
      int capacity) {
    this.zoneId = zoneId;
    this.position = position;
    this.name = name;
    this.priceAmount = priceAmount;
    this.priceCurrency = priceCurrency;
    this.capacity = capacity;
  }

  UUID getZoneId() {
    return zoneId;
  }

  String getName() {
    return name;
  }

  BigDecimal getPriceAmount() {
    return priceAmount;
  }

  String getPriceCurrency() {
    return priceCurrency;
  }

  int getCapacity() {
    return capacity;
  }

  /** Same zone data and position: used to avoid rewriting the zones when nothing changed. */
  boolean sameAs(ProjectedZoneEmbeddable other) {
    return zoneId.equals(other.zoneId)
        && position == other.position
        && name.equals(other.name)
        && priceAmount.compareTo(other.priceAmount) == 0
        && priceCurrency.equals(other.priceCurrency)
        && capacity == other.capacity;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProjectedZoneEmbeddable zone && sameAs(zone);
  }

  @Override
  public int hashCode() {
    return Objects.hash(zoneId, position, name, priceCurrency, capacity);
  }
}
