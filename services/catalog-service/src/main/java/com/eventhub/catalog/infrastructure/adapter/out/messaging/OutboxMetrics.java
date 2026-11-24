package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Backlog of the outbox, read from the table at scrape time: how many rows wait to be sent, how old
 * the oldest unpublished one is, and how many ran out of attempts. A growing age with a flat
 * pending count means the relay is stuck; any parked row needs a person.
 */
@Component
class OutboxMetrics implements MeterBinder {

  private final JdbcClient jdbc;
  private final OutboxProperties properties;
  private final Clock clock;

  OutboxMetrics(JdbcClient jdbc, OutboxProperties properties, Clock clock) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("outbox.pending", this, OutboxMetrics::pending)
        .description("Unpublished rows still being retried")
        .register(registry);
    Gauge.builder("outbox.parked", this, OutboxMetrics::parked)
        .description("Unpublished rows that ran out of attempts")
        .register(registry);
    Gauge.builder("outbox.oldest.age", this, OutboxMetrics::oldestAgeSeconds)
        .baseUnit("seconds")
        .description("Age of the oldest unpublished row, 0 when there is none")
        .register(registry);
  }

  private double pending() {
    return count("published_at IS NULL AND attempts < :max");
  }

  private double parked() {
    return count("published_at IS NULL AND attempts >= :max");
  }

  private double count(String condition) {
    return jdbc.sql("SELECT count(*) FROM outbox_event WHERE " + condition)
        .param("max", properties.maxAttempts())
        .query(Long.class)
        .single();
  }

  private double oldestAgeSeconds() {
    return jdbc.sql("SELECT min(created_at) FROM outbox_event WHERE published_at IS NULL")
        .query(OffsetDateTime.class)
        .optional()
        .map(oldest -> Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0)
        .map(age -> Math.max(0, age))
        .orElse(0.0);
  }
}
