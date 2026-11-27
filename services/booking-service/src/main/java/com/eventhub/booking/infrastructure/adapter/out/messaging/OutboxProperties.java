package com.eventhub.booking.infrastructure.adapter.out.messaging;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning of the outbox relay ({@code eventhub.outbox.*}). Whether the scheduled poller runs at all
 * is {@code eventhub.outbox.enabled}, read by the poller itself.
 *
 * @param batchSize most rows one pass locks and sends
 * @param pollInterval pause between passes of the scheduled poller
 * @param maxAttempts failed attempts after which a row is parked (no longer retried)
 * @param initialBackoff wait after the first failure; doubles on each further one
 * @param maxBackoff ceiling of that wait
 * @param confirmTimeout how long to wait for the broker's confirmation of one message
 */
@Validated
@ConfigurationProperties("eventhub.outbox")
public record OutboxProperties(
    @DefaultValue("50") @Min(1) int batchSize,
    @DefaultValue("1s") @NotNull Duration pollInterval,
    @DefaultValue("10") @Min(1) int maxAttempts,
    @DefaultValue("2s") @NotNull Duration initialBackoff,
    @DefaultValue("5m") @NotNull Duration maxBackoff,
    @DefaultValue("5s") @NotNull Duration confirmTimeout) {

  public OutboxProperties {
    if (initialBackoff != null && maxBackoff != null && maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException("max-backoff must not be shorter than initial-backoff");
    }
  }
}
