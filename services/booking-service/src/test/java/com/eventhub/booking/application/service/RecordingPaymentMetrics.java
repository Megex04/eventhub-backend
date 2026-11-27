package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.out.PaymentMetrics;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Remembers which bookings had an immediate refund fail. */
public final class RecordingPaymentMetrics implements PaymentMetrics {

  private final List<UUID> failures = new CopyOnWriteArrayList<>();

  @Override
  public void immediateRefundFailed(UUID bookingId) {
    failures.add(bookingId);
  }

  public List<UUID> immediateRefundFailures() {
    return List.copyOf(failures);
  }
}
