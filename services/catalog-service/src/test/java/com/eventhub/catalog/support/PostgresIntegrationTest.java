package com.eventhub.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Base of the integration tests: a real PostgreSQL and a real RabbitMQ, both shared by the JVM.
 * Flyway migrates the shared container's schema when the first context starts and Hibernate runs
 * with {@code ddl-auto=validate}, exactly as in production. Tables are emptied, the clock rewound
 * and the repository hook cleared before every test.
 */
@SpringBootTest
@ContextConfiguration(
    initializers = {SharedPostgres.class, SharedRabbit.class, TestOutboxSettings.class})
@Import(TestSupportConfig.class)
public abstract class PostgresIntegrationTest {

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected TestSupportConfig.AdjustableClock clock;
  @Autowired protected HookableEventRepository repositoryHook;
  @Autowired protected ControllableOutboxBroker broker;

  @BeforeEach
  void cleanDatabase() {
    clock.reset();
    repositoryHook.reset();
    broker.reset();
    jdbc.execute("TRUNCATE TABLE outbox_event, ticket_zone, event CASCADE");
  }
}
