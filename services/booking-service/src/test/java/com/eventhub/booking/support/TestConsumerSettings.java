package com.eventhub.booking.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Consumer settings for the tests. The catalog-events binding does not start with the context: a
 * context that stays cached would otherwise keep competing for the queue with the one under test,
 * and the metrics of whichever context won would be the ones missing. The consumer tests start it
 * (and stop it) themselves. Retries are short so that a message that exhausts them reaches the
 * dead-letter queue in about a second.
 */
public final class TestConsumerSettings
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  public static final String BINDING = "catalogEvents-in-0";

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    String consumer = "spring.cloud.stream.bindings." + BINDING + ".consumer.";
    TestPropertyValues.of(
            consumer + "auto-startup=false",
            consumer + "max-attempts=5",
            consumer + "back-off-initial-interval=200",
            consumer + "back-off-multiplier=2.0",
            consumer + "back-off-max-interval=800")
        .applyTo(context);
  }
}
