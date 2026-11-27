package com.eventhub.booking.application.port.in;

public interface ExpireBookingsUseCase {

  /** Expires one batch of bookings whose hold ended; returns how many it expired. */
  int expireDue(int batchSize);
}
