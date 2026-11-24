package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import com.eventhub.catalog.application.port.out.CorrelationIdProvider;
import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.application.port.out.IdGenerator;
import com.eventhub.catalog.domain.DomainEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
         payload, correlation_id, created_at, attempts)
      VALUES
        (:id, :aggregateType, :aggregateId, :eventType, :eventVersion,
         CAST(:payload AS jsonb), :correlationId, :createdAt, 0)
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
    for (DomainEvent event : events) {
      insert(envelopes.create(event, ids.newId(), correlationId, clock.instant()));
    }
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
