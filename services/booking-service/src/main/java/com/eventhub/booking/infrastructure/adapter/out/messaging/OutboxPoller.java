package com.eventhub.booking.infrastructure.adapter.out.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Calls the relay on a fixed delay. Off with {@code eventhub.outbox.enabled=false}, which the
 * integration tests use so that they drive {@link OutboxRelay#publishPending()} themselves.
 */
@Component
@ConditionalOnProperty(
    prefix = "eventhub.outbox",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class OutboxPoller {

  /** Bounds one poll so a steady stream of events cannot keep it from ever sleeping. */
  static final int MAX_PASSES = 100;

  private final OutboxRelay relay;

  OutboxPoller(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${eventhub.outbox.poll-interval:PT1S}")
  void poll() {
    // One pass sends at most one row per booking, so keep going while it makes progress.
    int pass = 0;
    while (pass < MAX_PASSES && relay.publishPending() > 0) {
      pass++;
    }
  }
}
