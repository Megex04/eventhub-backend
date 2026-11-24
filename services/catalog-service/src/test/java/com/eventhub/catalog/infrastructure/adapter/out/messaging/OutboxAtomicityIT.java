package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The aggregate and its outbox row are written in one transaction: whatever makes either write (or
 * the commit) fail leaves neither behind. The failures are real database errors raised by triggers
 * created for the test, so the whole stack, down to the commit, is what is being checked.
 */
class OutboxAtomicityIT extends MessagingIntegrationTest {

  private static final String FAILING_FUNCTION =
      """
      CREATE OR REPLACE FUNCTION it_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        RAISE EXCEPTION 'simulated outbox failure';
      END
      $$
      """;

  @AfterEach
  void dropTriggers() {
    jdbc.execute("DROP TRIGGER IF EXISTS it_fail_on_insert ON outbox_event");
    jdbc.execute("DROP TRIGGER IF EXISTS it_fail_at_commit ON outbox_event");
    jdbc.execute("DROP FUNCTION IF EXISTS it_fail_outbox()");
  }

  private void failEveryOutboxInsertImmediately() {
    jdbc.execute(FAILING_FUNCTION);
    jdbc.execute(
        "CREATE TRIGGER it_fail_on_insert BEFORE INSERT ON outbox_event "
            + "FOR EACH ROW EXECUTE FUNCTION it_fail_outbox()");
  }

  /** Both writes succeed; the transaction fails only when it tries to commit. */
  private void failEveryOutboxInsertAtCommit() {
    jdbc.execute(FAILING_FUNCTION);
    jdbc.execute(
        "CREATE CONSTRAINT TRIGGER it_fail_at_commit AFTER INSERT ON outbox_event "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION it_fail_outbox()");
  }

  private void publishExpectingFailure(UUID eventId) throws Exception {
    mockMvc
        .perform(withToken(post(EVENTS + "/" + eventId + "/publish"), organizer()))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "internal-error"))
        .andExpect(jsonPath("$.detail").value("The request could not be processed"));
  }

  private void assertEventUntouchedAndNothingStored(UUID eventId, long versionBefore) {
    assertThat(jdbc.queryForObject("SELECT status FROM event WHERE id = ?", String.class, eventId))
        .isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId))
        .isEqualTo(versionBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
  }

  @Test
  void should_store_neither_the_status_change_nor_a_row_when_the_outbox_write_fails()
      throws Exception {
    UUID eventId = createDraftWithZone(organizer());
    long versionBefore =
        jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId);
    failEveryOutboxInsertImmediately();

    publishExpectingFailure(eventId);

    assertEventUntouchedAndNothingStored(eventId, versionBefore);
  }

  @Test
  void should_store_neither_the_status_change_nor_a_row_when_the_commit_fails() throws Exception {
    UUID eventId = createDraftWithZone(organizer());
    long versionBefore =
        jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId);
    failEveryOutboxInsertAtCommit();

    publishExpectingFailure(eventId);

    assertEventUntouchedAndNothingStored(eventId, versionBefore);
  }

  @Test
  void should_publish_normally_when_the_failure_is_gone() throws Exception {
    UUID eventId = createDraftWithZone(organizer());
    failEveryOutboxInsertImmediately();
    publishExpectingFailure(eventId);
    dropTriggers();

    publishViaApi(eventId);

    assertThat(jdbc.queryForObject("SELECT status FROM event WHERE id = ?", String.class, eventId))
        .isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
        .isEqualTo(1);
  }
}
