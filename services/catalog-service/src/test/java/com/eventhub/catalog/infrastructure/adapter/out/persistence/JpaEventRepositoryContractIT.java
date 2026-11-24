package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.port.out.EventRepositoryContract;
import com.eventhub.catalog.support.SharedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs the repository contract against the JPA adapter and a real PostgreSQL. It cannot extend
 * {@code PostgresIntegrationTest} (the contract is already its superclass), so it repeats the same
 * two annotations, which keeps its Spring context identical and therefore shared.
 */
@SpringBootTest
@ContextConfiguration(initializers = SharedPostgres.class)
class JpaEventRepositoryContractIT extends EventRepositoryContract {

  @Autowired private EventRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @Override
  protected EventRepository repository() {
    return repository;
  }

  @Override
  protected void resetStorage() {
    jdbc.execute("TRUNCATE TABLE outbox_event, ticket_zone, event CASCADE");
  }
}
