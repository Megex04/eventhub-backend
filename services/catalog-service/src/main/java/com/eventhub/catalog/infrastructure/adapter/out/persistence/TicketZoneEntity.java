package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ticket_zone")
class TicketZoneEntity {

  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private EventEntity event;

  @Column(nullable = false)
  private short position;

  @Column(nullable = false, length = 60)
  private String name;

  @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal priceAmount;

  @Column(name = "price_currency", nullable = false, length = 3)
  private String priceCurrency;

  @Column(nullable = false)
  private int capacity;

  protected TicketZoneEntity() {}

  TicketZoneEntity(UUID id, EventEntity event) {
    this.id = id;
    this.event = event;
  }

  UUID getId() {
    return id;
  }

  short getPosition() {
    return position;
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

  void applyState(
      short position, String name, BigDecimal priceAmount, String priceCurrency, int capacity) {
    this.position = position;
    this.name = name;
    this.priceAmount = priceAmount;
    this.priceCurrency = priceCurrency;
    this.capacity = capacity;
  }
}
