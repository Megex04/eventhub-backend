package com.eventhub.booking.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the reconciler needs to find payments that no request is going to finish: refunds that
 * failed, and charges left INITIATED because the process died or the gateway timed out. Attempts
 * and the next retry instant are kept per payment so the retries back off.
 */
public interface ReconciliationRepository {

  /** Booking ids whose payment is REFUND_PENDING and due for a new attempt. */
  List<UUID> findRefundsDue(Instant now, int limit);

  /**
   * Booking ids whose payment is INITIATED since before {@code olderThan} and due for an attempt.
   */
  List<UUID> findStaleInitiatedDue(Instant olderThan, Instant now, int limit);

  int failedAttempts(UUID paymentId);

  void recordFailure(UUID paymentId, String error, Instant nextAttemptAt);

  long countRefundPending();

  long countStaleInitiated(Instant olderThan);
}
