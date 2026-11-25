package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.port.out.CorrelationIdProvider;
import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.port.out.IdGenerator;
import com.eventhub.catalog.domain.DomainEvent;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.infrastructure.config.MdcCorrelationIdProvider;
import com.eventhub.catalog.support.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OutboxPublisherIT extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00.123456Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Instant STARTS_AT = NOW.plus(Duration.ofDays(30));

  @Autowired private DomainEventPublisher publisher;
  @Autowired private EventRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JsonMapper json;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private IdGenerator idGenerator;
  @Autowired private CorrelationIdProvider correlationProvider;

  @AfterEach
  void clearCorrelation() {
    MDC.remove(MdcCorrelationIdProvider.MDC_KEY);
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private Event publishedEvent() {
    Event event =
        Event.create(
            UUID.randomUUID(),
            new UUID(1, 1),
            "Rock Fest",
            "",
            "Estadio",
            STARTS_AT,
            STARTS_AT.plus(Duration.ofHours(3)),
            List.of(TicketZone.create(UUID.randomUUID(), "General", Money.of("75", "PEN"), 200)),
            CLOCK);
    event.publish(CLOCK);
    return event;
  }

  private void publishInTransaction(List<DomainEvent> events) {
    transaction().executeWithoutResult(status -> publisher.publish(events));
  }

  private List<Map<String, Object>> rows() {
    return jdbc.queryForList(
        "SELECT id, aggregate_type, aggregate_id, event_type, event_version, payload::text AS payload,"
            + " correlation_id, created_at, published_at, attempts FROM outbox_event ORDER BY created_at, id");
  }

  @Test
  void should_store_a_pending_row_describing_the_event_when_event_published_is_written() {
    Event event = publishedEvent();

    publishInTransaction(event.pullDomainEvents());

    assertThat(rows())
        .singleElement()
        .satisfies(row -> {
          assertThat(row.get("aggregate_type")).isEqualTo("Event");
          assertThat(row.get("aggregate_id")).isEqualTo(event.id());
          assertThat(row.get("event_type")).isEqualTo("EventPublished");
          assertThat(row.get("event_version")).isEqualTo(1);
          assertThat(row.get("attempts")).isEqualTo(0);
          assertThat(row.get("published_at")).isNull();
          assertThat(row.get("created_at")).isNotNull();
        });
  }

  @Test
  void
      should_store_the_full_envelope_as_jsonb_with_the_row_id_as_message_id_when_an_event_is_recorded() {
    Event event = publishedEvent();

    publishInTransaction(event.pullDomainEvents());

    Map<String, Object> row = rows().getFirst();
    JsonNode envelope = json.readTree((String) row.get("payload"));
    assertThat(envelope.get("messageId").asString()).isEqualTo(row.get("id").toString());
    assertThat(envelope.get("correlationId").asString())
        .isEqualTo(row.get("correlation_id").toString());
    assertThat(envelope.get("type").asString()).isEqualTo("EventPublished");
    assertThat(envelope.get("version").asInt()).isEqualTo(1);
    assertThat(envelope.get("producer").asString()).isEqualTo("catalog-service");
    assertThat(envelope.get("data").get("eventId").asString()).isEqualTo(event.id().toString());
    assertThat(envelope.get("data").get("zones").get(0).get("price").get("amount").asString())
        .isEqualTo("75.00");
  }

  @Test
  void should_write_one_row_per_event_when_several_events_are_published_together() {
    Event event = publishedEvent();
    event.cancel("Venue unavailable", CLOCK);

    publishInTransaction(event.pullDomainEvents());

    assertThat(rows())
        .extracting(row -> row.get("event_type"))
        .containsExactlyInAnyOrder("EventPublished", "EventCancelled");
  }

  @Test
  void should_keep_created_at_strictly_increasing_when_the_clock_does_not_move_between_events() {
    // Rows are ordered by created_at and then by a random id: equal instants could swap
    // an EventPublished and the EventCancelled written right after it.
    OutboxDomainEventPublisher onFrozenClock =
        new OutboxDomainEventPublisher(jdbcClient, json, idGenerator, CLOCK, correlationProvider);
    Event event = publishedEvent();
    event.cancel("Venue unavailable", CLOCK);
    List<DomainEvent> events = event.pullDomainEvents();

    transaction().executeWithoutResult(status -> onFrozenClock.publish(events));

    List<Map<String, Object>> stored = rows();
    assertThat(stored)
        .extracting(row -> row.get("event_type"))
        .containsExactly("EventPublished", "EventCancelled");
    List<Instant> createdAt =
        stored.stream()
            .map(row -> ((java.sql.Timestamp) row.get("created_at")).toInstant())
            .toList();
    assertThat(createdAt.get(1)).isAfter(createdAt.get(0));
  }

  @Test
  void should_use_the_request_correlation_id_when_one_is_in_the_logging_context() {
    UUID correlationId = UUID.randomUUID();
    MDC.put(MdcCorrelationIdProvider.MDC_KEY, correlationId.toString());

    publishInTransaction(publishedEvent().pullDomainEvents());

    assertThat(rows().getFirst().get("correlation_id")).isEqualTo(correlationId);
  }

  @Test
  void should_generate_a_correlation_id_when_the_logging_context_has_none() {
    publishInTransaction(publishedEvent().pullDomainEvents());

    assertThat(rows().getFirst().get("correlation_id")).isInstanceOf(UUID.class).isNotNull();
  }

  @Test
  void should_generate_a_correlation_id_when_the_logging_context_value_is_not_a_uuid() {
    MDC.put(MdcCorrelationIdProvider.MDC_KEY, "not-a-uuid");

    publishInTransaction(publishedEvent().pullDomainEvents());

    assertThat(rows().getFirst().get("correlation_id")).isInstanceOf(UUID.class);
  }

  @Test
  void should_share_one_correlation_id_when_a_single_call_publishes_several_events() {
    Event event = publishedEvent();
    event.cancel("Venue unavailable", CLOCK);

    publishInTransaction(event.pullDomainEvents());

    assertThat(rows()).hasSize(2);
    assertThat(rows().stream().map(row -> row.get("correlation_id")).distinct()).hasSize(1);
  }

  @Test
  void should_write_nothing_when_there_are_no_events() {
    publishInTransaction(List.of());

    assertThat(rows()).isEmpty();
  }

  @Test
  void should_discard_the_rows_when_the_surrounding_transaction_rolls_back() {
    List<DomainEvent> events = publishedEvent().pullDomainEvents();

    assertThatThrownBy(() ->
            transaction()
                .executeWithoutResult(status -> {
                  publisher.publish(events);
                  throw new IllegalStateException("boom");
                }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rows()).isEmpty();
  }

  @Test
  void should_commit_the_aggregate_and_its_events_together_when_the_transaction_succeeds() {
    Event event = publishedEvent();

    transaction()
        .executeWithoutResult(status -> {
          repository.save(event);
          publisher.publish(event.pullDomainEvents());
        });

    assertThat(jdbc.queryForObject("SELECT count(*) FROM event", Integer.class)).isEqualTo(1);
    assertThat(rows()).hasSize(1);
  }

  @Test
  void should_roll_back_the_aggregate_and_its_events_together_when_the_transaction_fails() {
    Event event = publishedEvent();

    assertThatThrownBy(() ->
            transaction()
                .executeWithoutResult(status -> {
                  repository.save(event);
                  publisher.publish(event.pullDomainEvents());
                  throw new IllegalStateException("boom");
                }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM event", Integer.class)).isZero();
    assertThat(rows()).isEmpty();
  }

  @Test
  void should_refuse_to_write_events_when_there_is_no_surrounding_transaction() {
    List<DomainEvent> events = publishedEvent().pullDomainEvents();

    assertThatThrownBy(() -> publisher.publish(events))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(rows()).isEmpty();
  }
}
