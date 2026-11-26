package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.ReconciliationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the reconciler's bookkeeping on {@code payment} with plain SQL. */
@Repository
@Transactional
public class JdbcReconciliationRepository implements ReconciliationRepository {

  private final JdbcClient jdbc;

  public JdbcReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findRefundsDue(Instant now, int limit) {
    return jdbc.sql(
            "SELECT booking_id FROM payment WHERE status = 'REFUND_PENDING'"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= :now)"
                + " ORDER BY created_at, id LIMIT :limit")
        .param("now", SqlTime.of(now))
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findStaleInitiatedDue(Instant olderThan, Instant now, int limit) {
    return jdbc.sql(
            "SELECT booking_id FROM payment WHERE status = 'INITIATED'"
                + " AND created_at <= :olderThan"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= :now)"
                + " ORDER BY created_at, id LIMIT :limit")
        .param("olderThan", SqlTime.of(olderThan))
        .param("now", SqlTime.of(now))
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public int failedAttempts(UUID paymentId) {
    return jdbc.sql("SELECT attempts FROM payment WHERE id = :id")
        .param("id", paymentId)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  @Override
  public void recordFailure(UUID paymentId, String error, Instant nextAttemptAt) {
    jdbc.sql(
            "UPDATE payment SET attempts = attempts + 1, last_error = :error,"
                + " next_attempt_at = :next WHERE id = :id")
        .param("id", paymentId)
        .param("error", error)
        .param("next", SqlTime.of(nextAttemptAt))
        .update();
  }

  @Override
  @Transactional(readOnly = true)
  public long countRefundPending() {
    return jdbc.sql("SELECT count(*) FROM payment WHERE status = 'REFUND_PENDING'")
        .query(Long.class)
        .single();
  }

  @Override
  @Transactional(readOnly = true)
  public long countStaleInitiated(Instant olderThan) {
    return jdbc.sql("SELECT count(*) FROM payment WHERE status = 'INITIATED' AND created_at <= :t")
        .param("t", SqlTime.of(olderThan))
        .query(Long.class)
        .single();
  }
}
