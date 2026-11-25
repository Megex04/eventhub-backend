package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import com.eventhub.catalog.application.port.out.CorrelationIdProvider;
import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.application.port.out.IdGenerator;
import com.eventhub.catalog.domain.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox, write side: stores each event in {@code outbox_event} within the
 * transaction that saves the aggregate. A separate publisher (milestone 6) ships the rows to the
 * broker, which makes delivery at-least-once.
 */
@Component
public class OutboxDomainEventPublisher implements DomainEventPublisher {

  private static final String INSERT =
      """
      INSERT INTO outbox_event
        (id, aggregate_type, aggregate_id, event_type, event_version,
         payload, correlation_id, created_at, next_attempt_at, attempts)
      VALUES
        (:id, :aggregateType, :aggregateId, :eventType, :eventVersion,
         CAST(:payload AS jsonb), :correlationId, :createdAt, :createdAt, 0)
      """;

  private final JdbcClient jdbc;
  private final OutboxEnvelopeFactory envelopes;
  private final IdGenerator ids;
  private final Clock clock;
  private final CorrelationIdProvider correlation;

  public OutboxDomainEventPublisher(
      JdbcClient jdbc,
      JsonMapper json,
      IdGenerator ids,
      Clock clock,
      CorrelationIdProvider correlation) {
    this.jdbc = jdbc;
    this.envelopes = new OutboxEnvelopeFactory(json);
    this.ids = ids;
    this.clock = clock;
    this.correlation = correlation;
  }

  /** MANDATORY: an event written outside the aggregate's transaction could be lost or orphaned. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(List<DomainEvent> events) {
    UUID correlationId = correlation.current().orElseGet(ids::newId);
    Instant previous = null;
    for (DomainEvent event : events) {
      Instant createdAt = after(previous, clock.instant());
      insert(envelopes.create(event, ids.newId(), correlationId, createdAt));
      previous = createdAt;
    }
  }

  /**
   * The relay orders rows by {@code created_at} and only then by a random id, so two events written
   * by one call must never share an instant: a published event and the cancellation recorded right
   * after it could otherwise swap. One microsecond is the precision the column stores.
   */
  private static Instant after(Instant previous, Instant now) {
    return previous == null || now.isAfter(previous) ? now : previous.plus(1, ChronoUnit.MICROS);
  }

  private void insert(OutboxMessage message) {
    jdbc.sql(INSERT)
        .param("id", message.id())
        .param("aggregateType", message.aggregateType())
        .param("aggregateId", message.aggregateId())
        .param("eventType", message.eventType())
        .param("eventVersion", message.eventVersion())
        .param("payload", message.payload())
        .param("correlationId", message.correlationId())
        .param("createdAt", OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC))
        .update();
  }
}
