package com.eventhub.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Base of the integration tests that need a database. Flyway migrates the shared container's schema
 * when the first context starts and Hibernate runs with {@code ddl-auto=validate}, exactly as in
 * production. Tables are emptied before every test.
 */
@SpringBootTest
@ContextConfiguration(initializers = SharedPostgres.class)
public abstract class PostgresIntegrationTest {

  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void cleanDatabase() {
    jdbc.execute("TRUNCATE TABLE outbox_event, ticket_zone, event CASCADE");
  }
}
