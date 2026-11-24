package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.support.PostgresIntegrationTest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Talks to the schema directly with SQL: the constraints are the last line of defence. */
class SchemaConstraintsIT extends PostgresIntegrationTest {

  private static final String VALID_STARTS = "'2030-01-01T10:00:00Z'";
  private static final String VALID_ENDS = "'2030-01-01T12:00:00Z'";

  @Autowired private PlatformTransactionManager transactionManager;

  private UUID insertEvent(
      String status, String cancelReason, String startsAt, String endsAt, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO event (id, organizer_id, title, venue, starts_at, ends_at, status,
                           cancel_reason, created_at, updated_at)
        VALUES (?, ?, %s, 'Venue', %s, %s, '%s', %s, now(), now())
        """
            .formatted(
                title, startsAt, endsAt, status, cancelReason == null ? "NULL" : cancelReason),
        id,
        UUID.randomUUID());
    return id;
  }

  private UUID insertValidEvent() {
    return insertEvent("DRAFT", null, VALID_STARTS, VALID_ENDS, "'Rock Fest'");
  }

  private void insertZone(UUID eventId, String name, String amount, String currency, int capacity) {
    jdbc.update(
        """
        INSERT INTO ticket_zone (id, event_id, position, name, price_amount, price_currency, capacity)
        VALUES (?, ?, 0, ?, ?::numeric, ?, ?)
        """,
        UUID.randomUUID(),
        eventId,
        name,
        amount,
        currency,
        capacity);
  }

  @Test
  void should_accept_a_well_formed_event_with_a_zone() {
    UUID eventId = insertValidEvent();

    insertZone(eventId, "VIP", "250.50", "PEN", 100);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_zone", Integer.class)).isEqualTo(1);
  }

  static Stream<Arguments> invalidEvents() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "unknown status",
                new Row("ARCHIVED", null, VALID_STARTS, VALID_ENDS, "'Rock Fest'"))),
        Arguments.of(
            Named.of(
                "ends_at equal to starts_at",
                new Row("DRAFT", null, VALID_STARTS, VALID_STARTS, "'Rock Fest'"))),
        Arguments.of(
            Named.of(
                "ends_at before starts_at",
                new Row("DRAFT", null, VALID_ENDS, VALID_STARTS, "'Rock Fest'"))),
        Arguments.of(
            Named.of(
                "title shorter than 3", new Row("DRAFT", null, VALID_STARTS, VALID_ENDS, "'ab'"))),
        Arguments.of(
            Named.of(
                "cancel reason on a published event",
                new Row("PUBLISHED", "'Because'", VALID_STARTS, VALID_ENDS, "'Rock Fest'"))),
        Arguments.of(
            Named.of(
                "cancelled event without reason",
                new Row("CANCELLED", null, VALID_STARTS, VALID_ENDS, "'Rock Fest'"))));
  }

  record Row(String status, String cancelReason, String startsAt, String endsAt, String title) {}

  @ParameterizedTest
  @MethodSource("invalidEvents")
  void should_reject_an_event_row_that_breaks_a_check_constraint(Row row) {
    assertThatThrownBy(() ->
            insertEvent(
                row.status(), row.cancelReason(), row.startsAt(), row.endsAt(), row.title()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @MethodSource("invalidZones")
  void should_reject_a_zone_row_that_breaks_a_check_constraint(
      String amount, String currency, int capacity) {
    UUID eventId = insertValidEvent();

    assertThatThrownBy(() -> insertZone(eventId, "VIP", amount, currency, capacity))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  static Stream<Arguments> invalidZones() {
    return Stream.of(
        Arguments.of(Named.of("zero capacity", "10.00"), "PEN", 0),
        Arguments.of(Named.of("capacity above 100000", "10.00"), "PEN", 100_001),
        Arguments.of(Named.of("negative price", "-0.01"), "PEN", 10),
        Arguments.of(Named.of("lowercase currency", "10.00"), "pen", 10),
        Arguments.of(Named.of("two-letter currency", "10.00"), "PE", 10));
  }

  @Test
  void should_reject_a_zone_name_that_repeats_in_the_event_ignoring_case() {
    UUID eventId = insertValidEvent();
    insertZone(eventId, "VIP", "10.00", "PEN", 10);

    assertThatThrownBy(() -> insertZone(eventId, "vip", "10.00", "PEN", 10))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void should_accept_the_same_zone_name_in_different_events() {
    insertZone(insertValidEvent(), "VIP", "10.00", "PEN", 10);

    insertZone(insertValidEvent(), "VIP", "10.00", "PEN", 10);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_zone", Integer.class)).isEqualTo(2);
  }

  @Test
  void should_defer_the_zone_name_check_until_commit_when_a_name_is_replaced() {
    UUID eventId = insertValidEvent();
    insertZone(eventId, "VIP", "10.00", "PEN", 10);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          // Inserting before deleting would violate a plain unique index immediately.
          insertZone(eventId, "vip", "99.00", "PEN", 5);
          jdbc.update("DELETE FROM ticket_zone WHERE name = 'VIP'");
        });

    assertThat(jdbc.queryForList("SELECT name FROM ticket_zone", String.class))
        .containsExactly("vip");
  }

  @Test
  void should_still_reject_a_duplicate_name_when_it_survives_until_commit() {
    UUID eventId = insertValidEvent();
    insertZone(eventId, "VIP", "10.00", "PEN", 10);

    assertThatThrownBy(() ->
            new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> insertZone(eventId, "Vip", "10.00", "PEN", 10)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_zone", Integer.class)).isEqualTo(1);
  }

  @Test
  void should_store_the_lowercase_name_key_when_a_zone_is_inserted() {
    insertZone(insertValidEvent(), "Platea ALTA", "10.00", "PEN", 10);

    assertThat(jdbc.queryForObject("SELECT name_key FROM ticket_zone", String.class))
        .isEqualTo("platea alta");
  }

  @Test
  void should_delete_zones_when_their_event_is_deleted() {
    UUID eventId = insertValidEvent();
    insertZone(eventId, "VIP", "10.00", "PEN", 10);

    jdbc.update("DELETE FROM event WHERE id = ?", eventId);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_zone", Integer.class)).isZero();
  }

  @Test
  void should_start_every_event_at_version_zero_when_the_row_is_inserted() {
    UUID eventId = insertValidEvent();

    assertThat(jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId))
        .isZero();
  }

  @Test
  void should_index_only_unpublished_outbox_rows_for_the_poller() {
    String definition =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_event_pending'",
            String.class);

    assertThat(definition).contains("(created_at, id)").contains("WHERE (published_at IS NULL)");
  }

  @Test
  void should_index_only_published_events_for_the_public_listing() {
    String definition =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_event_published_starts_at'",
            String.class);

    assertThat(definition).contains("(starts_at, id)").contains("(status)::text = 'PUBLISHED'");
  }
}
