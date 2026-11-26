package com.eventhub.booking.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable of the service, bound from {@code eventhub.booking.*} and validated at startup: an
 * invalid value (a zero hold, no tickets per booking, a backoff cap below its start) stops the
 * application from starting instead of failing later in the middle of a request. There are no
 * defaults in code; {@code application.yml} is where they live.
 */
@Validated
@ConfigurationProperties("eventhub.booking")
public record BookingProperties(
    /** How long a PENDING booking keeps its tickets. */
    @NotNull @DurationMin(nanos = 1) Duration holdDuration,
    /** Most tickets one booking may hold in total. */
    @Min(1) int maxTickets,
    @NotNull @Valid Expiration expiration,
    @NotNull @Valid Reconciler reconciler,
    @NotNull @Valid Cascade cascade) {

  /** The job that expires bookings whose hold ended. */
  public record Expiration(
      boolean enabled,
      @Min(1) int batchSize,
      @NotNull @DurationMin(nanos = 1) Duration pollInterval) {}

  /** The job that finishes pending refunds and charges left initiated. */
  public record Reconciler(
      boolean enabled,
      @NotNull @DurationMin(nanos = 0) Duration initiatedStaleAfter,
      @NotNull @DurationMin(nanos = 1) Duration initialBackoff,
      @NotNull @DurationMin(nanos = 1) Duration maxBackoff,
      @Min(1) int batchSize,
      @NotNull @DurationMin(nanos = 1) Duration pollInterval) {

    @AssertTrue(message = "max-backoff must not be below initial-backoff")
    boolean isBackoffRangeValid() {
      return initialBackoff == null
          || maxBackoff == null
          || maxBackoff.compareTo(initialBackoff) >= 0;
    }
  }

  /** Bookings cancelled per batch when an event is cancelled. */
  public record Cascade(@Min(1) int batchSize) {}
}
