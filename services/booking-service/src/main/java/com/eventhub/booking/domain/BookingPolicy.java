package com.eventhub.booking.domain;

import java.time.Duration;

/**
 * Tunable booking rules, supplied by configuration so the domain holds no magic numbers: how long a
 * PENDING booking keeps its tickets and how many tickets one booking may hold in total.
 */
public record BookingPolicy(Duration holdDuration, int maxTickets) {

  public BookingPolicy {
    Validation.required("holdDuration", holdDuration);
    if (holdDuration.isZero() || holdDuration.isNegative()) {
      throw new InvalidValueException("holdDuration", "holdDuration must be positive");
    }
    Validation.atLeast("maxTickets", maxTickets, 1);
  }
}
