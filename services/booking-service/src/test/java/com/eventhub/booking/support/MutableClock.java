package com.eventhub.booking.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so time-dependent rules are exercised without waiting. */
public final class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  public synchronized void advance(Duration duration) {
    this.now = now.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
