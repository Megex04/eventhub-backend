package com.eventhub.booking.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test JVM, started the first time this class loads and
 * removed by Testcontainers' Ryuk when the JVM exits. Registered as a context initializer (rather
 * than a {@code @DynamicPropertySource}) so every test class gets an identical context
 * configuration and Spring reuses a single cached context instead of booting one per class.
 *
 * <p>Known duplication with catalog-service (ADR 0007): to be extracted once a third service needs
 * it.
 */
public final class SharedPostgres
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withDatabaseName("booking")
          // Every cached Spring context keeps its own pool: leave room for all of them.
          .withCommand("postgres", "-c", "max_connections=300");

  static {
    POSTGRES.start();
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
            // The stress tests run hundreds of threads against one pool: let them queue for a
            // connection instead of failing.
            "spring.datasource.hikari.maximum-pool-size=40",
            // A context that is cached but idle must not sit on 40 connections (Hikari keeps
            // minimum-idle equal to the maximum by default): it shrinks to one after 10 s.
            "spring.datasource.hikari.minimum-idle=1",
            "spring.datasource.hikari.idle-timeout=10000",
            "spring.datasource.hikari.connection-timeout=60000",
            // Required by the resource server config; tests replace the decoder, so it is never
            // called.
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak.test/realms/eventhub")
        .applyTo(context);
  }
}
