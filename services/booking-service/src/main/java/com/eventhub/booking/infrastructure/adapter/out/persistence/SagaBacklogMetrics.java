package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.ReconciliationRepository;
import com.eventhub.booking.infrastructure.config.BookingProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the expiration job and the reconciler leave undone, read from the tables at scrape time.
 * Each gauge is zero when things are healthy: {@code refund_pending} are refunds the gateway has
 * not accepted yet, {@code initiated_stale} are charges nobody is finishing, and {@code
 * bookings.pending.overdue} are PENDING bookings whose hold ended and still hold tickets (the
 * expiration job is behind or stopped). A value that stays above zero is an alert.
 */
@Component
class SagaBacklogMetrics implements MeterBinder {

  private final ReconciliationRepository reconciliation;
  private final JdbcClient jdbc;
  private final BookingProperties properties;
  private final Clock clock;

  SagaBacklogMetrics(
      ReconciliationRepository reconciliation,
      JdbcClient jdbc,
      BookingProperties properties,
      Clock clock) {
    this.reconciliation = reconciliation;
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("refund_pending", reconciliation, ReconciliationRepository::countRefundPending)
        .description("Payments whose refund is still to be completed")
        .register(registry);
    Gauge.builder("initiated_stale", this, SagaBacklogMetrics::initiatedStale)
        .description("Charges left INITIATED for longer than the reconciler threshold")
        .register(registry);
    Gauge.builder("bookings.pending.overdue", this, SagaBacklogMetrics::pendingOverdue)
        .description("PENDING bookings whose hold ended and have not been expired")
        .register(registry);
  }

  private double initiatedStale() {
    return reconciliation.countStaleInitiated(
        clock.instant().minus(properties.reconciler().initiatedStaleAfter()));
  }

  private double pendingOverdue() {
    return jdbc.sql("SELECT count(*) FROM booking WHERE status = 'PENDING' AND expires_at <= :now")
        .param("now", java.sql.Timestamp.from(clock.instant()))
        .query(Long.class)
        .single();
  }
}
