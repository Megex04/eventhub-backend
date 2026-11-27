package com.eventhub.booking.application.port.in;

public interface ApplyEventRescheduledUseCase {

  /** Throws EventProjectionMissingException when the event was never published here (retry). */
  void apply(EventRescheduledMessage message);
}
