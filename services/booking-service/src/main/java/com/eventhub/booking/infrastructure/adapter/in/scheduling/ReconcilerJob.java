package com.eventhub.booking.infrastructure.adapter.in.scheduling;

import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.application.port.in.ReconcileReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes pending refunds and resolves charges left INITIATED, on a fixed delay. Thresholds and
 * backoff live in {@code ReconcilerPolicy}. Off with {@code
 * eventhub.booking.reconciler.enabled=false}; tests then call {@link #reconcile()} themselves.
 */
@Component
@ConditionalOnProperty(
    prefix = "eventhub.booking.reconciler",
    name = "enabled",
    havingValue = "true")
public class ReconcilerJob {

  private static final Logger log = LoggerFactory.getLogger(ReconcilerJob.class);

  private final ReconcileRefundsUseCase reconciler;

  ReconcilerJob(ReconcileRefundsUseCase reconciler) {
    this.reconciler = reconciler;
  }

  @Scheduled(fixedDelayString = "${eventhub.booking.reconciler.poll-interval}")
  public void reconcile() {
    ReconcileReport report = reconciler.reconcile();
    if (report.refundsCompleted()
            + report.refundsFailed()
            + report.orphansConfirmed()
            + report.orphansRefunded()
            + report.orphansDeclined()
            + report.orphansUnresolved()
        > 0) {
      log.info("Reconciled payments: {}", report);
    }
  }
}
