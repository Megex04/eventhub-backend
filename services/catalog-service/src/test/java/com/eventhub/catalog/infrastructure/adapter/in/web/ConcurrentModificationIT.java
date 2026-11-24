package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the client sees when another transaction lands between the moment the use case loads an
 * event and the moment it saves it. The "other transaction" is a separate autocommit connection,
 * run by a hook right after the load, so the window is hit every time.
 */
class ConcurrentModificationIT extends WebIntegrationTest {

  @Autowired private DataSource dataSource;

  /** Runs once, on the first load after it is armed. */
  private void afterNextLoad(OtherTransaction action) {
    AtomicBoolean fired = new AtomicBoolean();
    repositoryHook.onAfterLoad(eventId -> {
      if (fired.compareAndSet(false, true)) {
        try {
          action.runFor(eventId);
        } catch (SQLException e) {
          throw new IllegalStateException("The competing transaction failed", e);
        }
      }
    });
  }

  @FunctionalInterface
  private interface OtherTransaction {
    void runFor(UUID eventId) throws SQLException;
  }

  private void commitInAnotherTransaction(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  @Test
  void should_answer_409_with_its_own_type_when_the_event_changes_between_load_and_save()
      throws Exception {
    UUID id = createDraft(organizer());
    afterNextLoad(eventId ->
        commitInAnotherTransaction(
            "UPDATE event SET title = 'Changed elsewhere', version = version + 1 WHERE id = ?",
            eventId));

    mockMvc
        .perform(json(withToken(put(EVENTS + "/" + id), organizer()), eventJson("Renamed Fest")))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "concurrent-modification"))
        .andExpect(jsonPath("$.status").value(409));

    assertThat(jdbc.queryForObject("SELECT title FROM event WHERE id = ?", String.class, id))
        .isEqualTo("Changed elsewhere");
  }

  @Test
  void should_answer_409_and_not_500_when_another_transaction_commits_the_same_zone_name()
      throws Exception {
    UUID id = createDraftWithZone(organizer());
    afterNextLoad(eventId ->
        commitInAnotherTransaction(
            """
            INSERT INTO ticket_zone
              (id, event_id, position, name, price_amount, price_currency, capacity)
            VALUES (?, ?, 9, 'VIP', 10.00, 'PEN', 10)
            """,
            UUID.randomUUID(),
            eventId));

    mockMvc
        .perform(
            json(
                withToken(post(EVENTS + "/" + id + "/zones"), organizer()),
                zoneJson("VIP", "80.00")))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "concurrent-modification"));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_zone WHERE event_id = ? AND name_key = 'vip'",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, id))
        .as("the rejected save leaves the event as it was")
        .isEqualTo(1L);
  }
}
