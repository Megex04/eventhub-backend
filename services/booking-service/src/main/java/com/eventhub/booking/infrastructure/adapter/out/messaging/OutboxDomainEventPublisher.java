package com.eventhub.booking.infrastructure.adapter.out.messaging;

import com.eventhub.booking.application.port.out.CorrelationIdProvider;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.IdGenerator;
import com.eventhub.booking.domain.DomainEvent;
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
 * transaction that changes the booking, so the state and its event commit or roll back together.
 * The relay ships the rows to the broker, which makes delivery at-least-once.
 *
 * <p><b>Order per booking is fixed by the database.</b> The relay sends the rows of one booking in
 * {@code created_at, id} order, so two events of the same booking must never share an instant, even
 * when they come from different transactions and the clock did not move (or moved back, between
 * instances). Each row therefore gets {@code max(created_at of that booking) + 1 microsecond} when
 * the clock would give it an instant that is not later. Writers of one booking are serialized by
 * the booking row (optimistic version claim), so the maximum read here is the previous event.
 *
 * <p>Only {@code created_at} is staggered. {@code next_attempt_at} is the clock's "now", so a row
 * is due immediately even when its ordering instant is a few microseconds ahead of the clock.
 */
@Component
public class OutboxDomainEventPublisher implements DomainEventPublisher {

  private static final String INSERT =
      """
      WITH stamp AS (
        SELECT GREATEST(
                 CAST(:now AS timestamptz),
                 COALESCE(
                   (SELECT max(created_at) + interval '1 microsecond'
                      FROM outbox_event WHERE aggregate_id = :aggregateId),
                   CAST(:now AS timestamptz))) AS at)
      INSERT INTO outbox_event
        (id, aggregate_type, aggregate_id, event_type, event_version,
         payload, correlation_id, created_at, next_attempt_at, attempts)
      SELECT :id, :aggregateType, :aggregateId, :eventType, :eventVersion,
             CAST(:payload AS jsonb), :correlationId, stamp.at, CAST(:now AS timestamptz), 0
        FROM stamp
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

  /** MANDATORY: an event written outside the booking's transaction could be lost or orphaned. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(List<DomainEvent> events) {
    UUID correlationId = correlation.current().orElseGet(ids::newId);
    for (DomainEvent event : events) {
      insert(envelopes.create(event, ids.newId(), correlationId));
    }
  }

  private void insert(OutboxEnvelopeFactory.Row row) {
    jdbc.sql(INSERT)
        .param("id", row.id())
        .param("aggregateType", row.aggregateType())
        .param("aggregateId", row.aggregateId())
        .param("eventType", row.eventType())
        .param("eventVersion", row.eventVersion())
        .param("payload", row.payload())
        .param("correlationId", row.correlationId())
        .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .update();
  }
}
