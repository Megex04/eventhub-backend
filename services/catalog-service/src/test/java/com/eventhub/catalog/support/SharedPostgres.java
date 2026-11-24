package com.eventhub.catalog.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test JVM, started the first time this class loads and
 * removed by Testcontainers' Ryuk when the JVM exits. Registered as a context initializer (rather
 * than a {@code @DynamicPropertySource}) so every test class gets an identical context
 * configuration and Spring reuses a single cached context instead of booting one per class.
 */
public final class SharedPostgres
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17").withDatabaseName("catalog");

  static {
    POSTGRES.start();
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword())
        .applyTo(context);
  }
}
