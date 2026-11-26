package com.eventhub.booking.infrastructure.adapter.out.payment;

import com.eventhub.booking.application.port.out.PaymentMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Logs and counts what the payment saga leaves for the reconciler. */
@Component
class MicrometerPaymentMetrics implements PaymentMetrics {

  static final String IMMEDIATE_REFUND_FAILED = "payments.refund.immediate.failed";

  private static final Logger log = LoggerFactory.getLogger(MicrometerPaymentMetrics.class);

  private final Counter immediateRefundFailed;

  MicrometerPaymentMetrics(MeterRegistry meters) {
    this.immediateRefundFailed =
        Counter.builder(IMMEDIATE_REFUND_FAILED)
            .description(
                "Refunds tried right after a cancellation or late charge that did not complete")
            .register(meters);
  }

  @Override
  public void immediateRefundFailed(UUID bookingId) {
    immediateRefundFailed.increment();
    log.warn(
        "The immediate refund of booking {} did not complete; it stays REFUND_PENDING for the"
            + " reconciler",
        bookingId);
  }
}
