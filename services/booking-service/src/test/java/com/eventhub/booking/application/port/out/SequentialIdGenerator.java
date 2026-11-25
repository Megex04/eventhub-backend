package com.eventhub.booking.application.port.out;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Predictable ids (a counter) so tests can name the booking or payment a service will create. */
public final class SequentialIdGenerator implements IdGenerator {

  private final AtomicLong next;

  public SequentialIdGenerator(long start) {
    this.next = new AtomicLong(start);
  }

  public static UUID idOf(long n) {
    return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(n));
  }

  @Override
  public UUID newId() {
    return idOf(next.getAndIncrement());
  }
}
