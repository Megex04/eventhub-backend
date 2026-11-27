package com.eventhub.booking.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * One RabbitMQ broker for the whole test JVM, like {@link SharedPostgres}. Tests that need to take
 * the broker away pause and unpause this container; it is never stopped, so the mapped port that
 * the cached application context holds stays valid.
 *
 * <p>Known duplication with catalog-service (ADR 0007): to be extracted once a third service needs
 * it.
 */
public final class SharedRabbit
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4");

  static {
    RABBIT.start();
  }

  /** Container id, to pause and unpause the broker through the Docker API. */
  public static String containerId() {
    return RABBIT.getContainerId();
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.rabbitmq.host=" + RABBIT.getHost(),
            "spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
            "spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
            "spring.rabbitmq.password=" + RABBIT.getAdminPassword())
        .applyTo(context);
  }
}
