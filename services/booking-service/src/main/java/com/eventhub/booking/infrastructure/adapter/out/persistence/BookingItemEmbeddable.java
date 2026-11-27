package com.eventhub.booking.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.UUID;

@Embeddable
class BookingItemEmbeddable {

  @Column(name = "zone_id", nullable = false)
  private UUID zoneId;

  @Column(nullable = false)
  private int position;

  @Column(name = "zone_name", nullable = false)
  private String zoneName;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal unitPriceAmount;

  @Column(name = "unit_price_currency", nullable = false, length = 3)
  private String unitPriceCurrency;

  protected BookingItemEmbeddable() {}

  BookingItemEmbeddable(
      UUID zoneId,
      int position,
      String zoneName,
      int quantity,
      BigDecimal unitPriceAmount,
      String unitPriceCurrency) {
    this.zoneId = zoneId;
    this.position = position;
    this.zoneName = zoneName;
    this.quantity = quantity;
    this.unitPriceAmount = unitPriceAmount;
    this.unitPriceCurrency = unitPriceCurrency;
  }

  UUID getZoneId() {
    return zoneId;
  }

  String getZoneName() {
    return zoneName;
  }

  int getQuantity() {
    return quantity;
  }

  BigDecimal getUnitPriceAmount() {
    return unitPriceAmount;
  }

  String getUnitPriceCurrency() {
    return unitPriceCurrency;
  }
}
