package com.eventhub.booking.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Relay settings for the tests: no background poller (tests drive {@code publishPending()}
 * themselves, so nothing sends behind their back), small batches so concurrent relays interleave, a
 * short confirmation timeout so a dead broker fails an attempt quickly, and a backoff of one second
 * that tests step over by advancing the clock.
 */
public final class TestOutboxSettings
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "eventhub.outbox.enabled=false",
            "eventhub.outbox.batch-size=5",
            "eventhub.outbox.max-attempts=5",
            "eventhub.outbox.initial-backoff=1s",
            "eventhub.outbox.max-backoff=10s",
            "eventhub.outbox.confirm-timeout=3s")
        .applyTo(context);
  }
}
