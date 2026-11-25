package com.eventhub.booking.application.port.in;

public interface ApplyEventCancelledUseCase {

  /**
   * Cancels the event here and, in batches of one transaction per booking, its bookings. Resumable:
   * a failure midway leaves what was done in place and the redelivered message continues.
   */
  void apply(EventCancelledMessage message);
}
