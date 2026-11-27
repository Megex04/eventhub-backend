package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records message ids. {@code ON CONFLICT DO NOTHING} makes two deliveries of the same message race
 * safely: the second waits for the first to commit and then sees the id already taken; if the first
 * rolls back, the second takes it.
 */
@Repository
@Transactional
public class JdbcProcessedMessageRepository implements ProcessedMessageRepository {

  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcProcessedMessageRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isProcessed(UUID messageId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_message WHERE message_id = :id)")
        .param("id", messageId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean markProcessed(UUID messageId) {
    return jdbc.sql(
                "INSERT INTO processed_message (message_id, processed_at) VALUES (:id, :at)"
                    + " ON CONFLICT (message_id) DO NOTHING")
            .param("id", messageId)
            .param("at", SqlTime.of(clock.instant()))
            .update()
        == 1;
  }
}
