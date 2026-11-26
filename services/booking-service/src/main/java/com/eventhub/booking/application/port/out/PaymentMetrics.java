package com.eventhub.booking.application.port.out;

import java.util.UUID;

/**
 * What operators need to see about payments. A port because the application layer neither logs nor
 * counts: the adapter does both.
 */
public interface PaymentMetrics {

  /**
   * The refund tried right after a cancellation or a late charge did not complete (the gateway
   * failed or the result could not be recorded). The payment stays REFUND_PENDING and the
   * reconciler finishes it.
   */
  void immediateRefundFailed(UUID bookingId);
}
