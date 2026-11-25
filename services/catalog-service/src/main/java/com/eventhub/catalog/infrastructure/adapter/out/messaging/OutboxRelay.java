package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox, read side: ships the rows the writer stored to the broker.
 *
 * <p>One pass ({@link #publishPending()}) is one transaction. It locks a batch of due rows with
 * {@code FOR UPDATE SKIP LOCKED}, so concurrent relays (several instances, or a test) never take
 * the same row, and sends them in {@code created_at, id} order. A row is marked published only
 * after the broker confirmed it. At the first failure the pass stops, so later rows are not sent
 * ahead of it, and the failure is recorded with an exponential backoff. If the process dies between
 * the broker's confirmation and the commit, the row is sent again with the same {@code messageId}:
 * delivery is at-least-once and consumers deduplicate.
 *
 * <p><b>Ordering is per aggregate.</b> A row is selected only if no earlier row of the same
 * aggregate is still unpublished, so a {@code reschedule} can never overtake a {@code publish} that
 * is backing off, while unrelated aggregates are not held up. A parked row (attempts exhausted)
 * keeps blocking its aggregate until someone resolves it: sending later events of an aggregate
 * whose earlier event was lost would hand consumers an impossible history.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int MAX_ERROR_LENGTH = 1000;
  private static final int MAX_BACKOFF_DOUBLINGS = 20;

  private static final String SELECT_DUE =
      """
      SELECT o.id, o.aggregate_type, o.aggregate_id, o.event_type, o.event_version,
             o.payload::text AS payload, o.correlation_id, o.created_at, o.attempts
        FROM outbox_event o
       WHERE o.published_at IS NULL
         AND o.attempts < :maxAttempts
         AND o.next_attempt_at <= :now
         AND NOT EXISTS (
               SELECT 1 FROM outbox_event earlier
                WHERE earlier.aggregate_id = o.aggregate_id
                  AND earlier.published_at IS NULL
                  AND (earlier.created_at, earlier.id) < (o.created_at, o.id))
       ORDER BY o.created_at, o.id
       LIMIT :batchSize
         FOR UPDATE OF o SKIP LOCKED
      """;

  private static final String MARK_PUBLISHED =
      "UPDATE outbox_event SET published_at = :now, last_error = NULL WHERE id = :id";

  private static final String MARK_FAILED =
      """
      UPDATE outbox_event
         SET attempts = :attempts, last_error = :error, next_attempt_at = :next
       WHERE id = :id
      """;

  private final JdbcClient jdbc;
  private final OutboxBroker broker;
  private final OutboxProperties properties;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final Counter published;
  private final Counter failures;
  private final Counter parked;

  public OutboxRelay(
      JdbcClient jdbc,
      OutboxBroker broker,
      OutboxProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager,
      MeterRegistry meters) {
    this.jdbc = jdbc;
    this.broker = broker;
    this.properties = properties;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
    this.published =
        Counter.builder("outbox.published")
            .description("Rows confirmed by the broker")
            .register(meters);
    this.failures =
        Counter.builder("outbox.failures").description("Failed send attempts").register(meters);
    this.parked =
        Counter.builder("outbox.parkings")
            .description("Times a row ran out of attempts and was parked")
            .register(meters);
  }

  /**
   * Runs one pass.
   *
   * @return how many rows were confirmed and marked published in this pass
   */
  public int publishPending() {
    Integer count = transaction.execute(status -> relayBatch());
    return count == null ? 0 : count;
  }

  private int relayBatch() {
    Instant now = clock.instant();
    int sent = 0;
    for (Pending row : lockDueRows(now)) {
      try {
        broker.publish(row.message());
      } catch (BrokerPublishException e) {
        recordFailure(row, e, now);
        break;
      }
      markPublished(row.message().id(), now);
      sent++;
    }
    return sent;
  }

  private List<Pending> lockDueRows(Instant now) {
    return jdbc.sql(SELECT_DUE)
        .param("maxAttempts", properties.maxAttempts())
        .param("now", utc(now))
        .param("batchSize", properties.batchSize())
        .query((rs, rowNum) ->
            new Pending(
                new OutboxMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getInt("event_version"),
                    rs.getString("payload"),
                    rs.getObject("correlation_id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                rs.getInt("attempts")))
        .list();
  }

  private void markPublished(UUID id, Instant now) {
    jdbc.sql(MARK_PUBLISHED).param("now", utc(now)).param("id", id).update();
    published.increment();
  }

  private void recordFailure(Pending row, BrokerPublishException failure, Instant now) {
    int attempts = row.attempts() + 1;
    Instant next = now.plus(backoff(row.attempts()));
    jdbc.sql(MARK_FAILED)
        .param("attempts", attempts)
        .param("error", truncate(failure.getMessage()))
        .param("next", utc(next))
        .param("id", row.message().id())
        .update();
    failures.increment();
    if (attempts >= properties.maxAttempts()) {
      parked.increment();
      log.error(
          "Outbox row {} ({}) is parked after {} failed attempts: {}",
          row.message().id(),
          row.message().eventType(),
          attempts,
          failure.getMessage());
    } else {
      log.warn(
          "Outbox row {} ({}) not sent (attempt {}); next try at {}: {}",
          row.message().id(),
          row.message().eventType(),
          attempts,
          next,
          failure.getMessage());
    }
  }

  /** Doubles from {@code initialBackoff} for each earlier failure, up to {@code maxBackoff}. */
  private Duration backoff(int failedBefore) {
    Duration delay =
        properties
            .initialBackoff()
            .multipliedBy(1L << Math.min(failedBefore, MAX_BACKOFF_DOUBLINGS));
    return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static String truncate(String text) {
    String value = text == null ? "unknown error" : text;
    return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
  }

  private record Pending(OutboxMessage message, int attempts) {}
}
