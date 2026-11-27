package com.eventhub.booking.application.service;

import java.time.Duration;

/**
 * Thresholds of the reconciler, supplied by configuration. {@code initiatedStaleAfter}: how old an
 * INITIATED payment must be before nobody is assumed to be finishing it. A failed attempt is
 * retried after {@code initialBackoff}, doubling each time up to {@code maxBackoff}.
 */
public record ReconcilerPolicy(
    Duration initiatedStaleAfter, Duration initialBackoff, Duration maxBackoff, int batchSize) {

  public ReconcilerPolicy {
    if (initiatedStaleAfter.isNegative()
        || initialBackoff.isZero()
        || initialBackoff.isNegative()
        || maxBackoff.compareTo(initialBackoff) < 0
        || batchSize < 1) {
      throw new IllegalArgumentException("Invalid reconciler policy");
    }
  }

  /** Backoff after {@code failedAttempts} failures: initial, then doubled, never above the cap. */
  Duration backoffAfter(int failedAttempts) {
    Duration delay = initialBackoff;
    for (int i = 0; i < failedAttempts && delay.compareTo(maxBackoff) < 0; i++) {
      delay = delay.multipliedBy(2);
    }
    return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
  }
}
