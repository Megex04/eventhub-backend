package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.catalog.support.TestTokens;
import com.eventhub.contracttesting.EventSchemas;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The administrative re-emission (ADR 0003), end to end: a real database, the real outbox relay and
 * a real broker with a test queue bound like a new consumer would bind its own.
 */
class ReplayEndpointIT extends MessagingIntegrationTest {

  private static final String REPLAY = "/actuator/replay";
  private static final String ADMIN_ID = "c2d9f6a1-5b3e-4f7a-8d10-2e4b6a8c0d03";

  @Autowired private DataSource dataSource;

  private record Catalog(UUID draft, UUID published, UUID rescheduled, UUID cancelled) {}

  private static String adminToken() {
    return TestTokens.admin(ADMIN_ID);
  }

  private ResultActions replay(String token, String body) throws Exception {
    return mockMvc.perform(json(withToken(post(REPLAY), token), body));
  }

  private ResultActions replayAsAdmin(String body) throws Exception {
    return replay(adminToken(), body);
  }

  /** One event per state; everything the API already queued is delivered and forgotten first. */
  private Catalog mixedCatalog() throws Exception {
    UUID draft = createDraftWithZone(organizer());
    UUID published = createPublished(organizer());
    UUID rescheduled = createPublished(organizer());
    rescheduleViaApi(rescheduled);
    UUID cancelled = createPublished(organizer());
    cancelViaApi(cancelled);
    drain();
    admin.purgeQueue(QUEUE);
    return new Catalog(draft, published, rescheduled, cancelled);
  }

  private Set<UUID> outboxRowIds() {
    return new HashSet<>(jdbc.queryForList("SELECT id FROM outbox_event", UUID.class));
  }

  private static String eventIdOf(Message message) {
    return field(message, "$.data.eventId");
  }

  private static String typeOf(Message message) {
    return field(message, "$.type");
  }

  private List<Message> messagesOf(List<Message> all, UUID eventId) {
    return all.stream().filter(m -> eventIdOf(m).equals(eventId.toString())).toList();
  }

  private Map<String, Object> eventRow(UUID eventId) {
    return jdbc.queryForMap(
        "SELECT status, title, venue, starts_at, ends_at, updated_at, cancel_reason, version"
            + " FROM event WHERE id = ?",
        eventId);
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

  // ---- what is replayed ----

  @Test
  void should_replay_the_current_state_of_published_and_cancelled_events_when_the_catalog_is_mixed()
      throws Exception {
    Catalog catalog = mixedCatalog();

    replayAsAdmin("{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events").value(3))
        .andExpect(jsonPath("$.messages").value(4))
        .andExpect(jsonPath("$.conflicts").value(0))
        .andExpect(jsonPath("$.next").doesNotExist());
    assertThat(drain()).isEqualTo(4);

    List<Message> messages = receive(4);
    assertThat(queueIsEmpty()).isTrue();
    assertThat(messagesOf(messages, catalog.published()).stream().map(m -> typeOf(m)))
        .containsExactly("EventPublished");
    assertThat(messagesOf(messages, catalog.rescheduled()).stream().map(m -> typeOf(m)))
        .containsExactly("EventPublished");
    assertThat(messagesOf(messages, catalog.cancelled()).stream().map(m -> typeOf(m)))
        .as("a cancelled event is replayed as its history: published, then cancelled")
        .containsExactly("EventPublished", "EventCancelled");
    assertThat(messagesOf(messages, catalog.draft())).as("a draft is never replayed").isEmpty();
  }

  @Test
  void should_carry_the_current_dates_and_venue_when_the_event_was_rescheduled() throws Exception {
    Catalog catalog = mixedCatalog();
    Map<String, Object> row = eventRow(catalog.rescheduled());

    replayAsAdmin("{\"eventId\":\"" + catalog.rescheduled() + "\"}").andExpect(status().isOk());
    drain();

    Message message = receive();
    assertThat(field(message, "$.data.startsAt"))
        .isEqualTo(((Timestamp) row.get("starts_at")).toInstant().toString());
    assertThat(field(message, "$.data.endsAt"))
        .isEqualTo(((Timestamp) row.get("ends_at")).toInstant().toString());
    assertThat(field(message, "$.data.venue")).isEqualTo((String) row.get("venue"));
    assertThat(field(message, "$.data.zones[0].name")).isEqualTo("General");
  }

  @Test
  void should_send_the_cancellation_reason_and_time_when_a_cancelled_event_is_replayed()
      throws Exception {
    Catalog catalog = mixedCatalog();
    Map<String, Object> row = eventRow(catalog.cancelled());

    replayAsAdmin("{\"eventId\":\"" + catalog.cancelled() + "\"}").andExpect(status().isOk());
    drain();

    List<Message> messages = receive(2);
    Message cancelled = messages.get(1);
    assertThat(routingKey(messages.get(0))).isEqualTo("catalog.event.published.v1");
    assertThat(routingKey(cancelled)).isEqualTo("catalog.event.cancelled.v1");
    assertThat(field(cancelled, "$.data.reason")).isEqualTo("Venue unavailable");
    assertThat(field(cancelled, "$.data.cancelledAt"))
        .isEqualTo(((Timestamp) row.get("updated_at")).toInstant().toString());
  }

  @Test
  void should_create_no_outbox_row_when_the_only_event_is_a_draft() throws Exception {
    UUID draft = createDraftWithZone(organizer());

    replayAsAdmin("{\"eventId\":\"" + draft + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events").value(0))
        .andExpect(jsonPath("$.messages").value(0));
    replayAsAdmin("{}").andExpect(status().isOk()).andExpect(jsonPath("$.events").value(0));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
  }

  // ---- messages ----

  @Test
  void
      should_give_every_replayed_message_a_new_id_and_a_valid_payload_when_the_catalog_is_replayed()
          throws Exception {
    mixedCatalog();
    Set<UUID> originalRows = outboxRowIds();

    replayAsAdmin("{}").andExpect(status().isOk());
    drain();

    List<Message> messages = receive(4);
    Set<String> ids = new HashSet<>();
    for (Message message : messages) {
      String messageId = field(message, "$.messageId");
      assertThat(originalRows)
          .as("a replay is a new message")
          .doesNotContain(UUID.fromString(messageId));
      assertThat(ids.add(messageId)).as("distinct ids").isTrue();
      assertThat(message.getMessageProperties().getMessageId()).isEqualTo(messageId);
      assertThat(EventSchemas.violations(typeOf(message), body(message))).isEmpty();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE id = ANY (?)",
                Integer.class,
                (Object) originalRows.toArray(UUID[]::new)))
        .as("the original rows stay as they were")
        .isEqualTo(originalRows.size());
  }

  @Test
  void should_tag_the_replayed_messages_with_the_correlation_id_when_the_request_sends_one()
      throws Exception {
    UUID published = createPublished(organizer());
    drain();
    admin.purgeQueue(QUEUE);
    UUID correlation = UUID.randomUUID();

    mockMvc
        .perform(
            json(withToken(post(REPLAY), adminToken()), "{\"eventId\":\"" + published + "\"}")
                .header("X-Correlation-Id", correlation.toString()))
        .andExpect(status().isOk());
    drain();

    assertThat(field(receive(), "$.correlationId")).isEqualTo(correlation.toString());
  }

  @Test
  void should_queue_the_replay_behind_pending_events_of_the_same_event_when_they_are_not_sent_yet()
      throws Exception {
    UUID id = createPublished(organizer());
    rescheduleViaApi(id);

    replayAsAdmin("{\"eventId\":\"" + id + "\"}").andExpect(status().isOk());

    assertThat(drain()).isEqualTo(3);
    List<Message> messages = receive(3);
    assertThat(messages.stream().map(MessagingIntegrationTest::routingKey))
        .as("the replay never jumps ahead of what the event already had queued")
        .containsExactly(
            "catalog.event.published.v1",
            "catalog.event.rescheduled.v1",
            "catalog.event.published.v1");
    assertThat(field(messages.get(2), "$.data.startsAt"))
        .as("and it describes the event as it is after the reschedule")
        .isEqualTo(field(messages.get(1), "$.data.startsAt"));
  }

  // ---- repeating it ----

  @Test
  void should_send_the_same_data_and_leave_the_events_alone_when_the_replay_runs_twice()
      throws Exception {
    Catalog catalog = mixedCatalog();
    Map<String, Object> before = eventRow(catalog.cancelled());

    replayAsAdmin("{}").andExpect(status().isOk()).andExpect(jsonPath("$.messages").value(4));
    drain();
    List<Message> first = receive(4);
    replayAsAdmin("{}").andExpect(status().isOk()).andExpect(jsonPath("$.messages").value(4));
    drain();
    List<Message> second = receive(4);

    assertThat(second.stream().map(m -> JsonPath.<Object>read(body(m), "$.data")).toList())
        .as("same business content both times")
        .containsExactlyElementsOf(
            first.stream().map(m -> JsonPath.<Object>read(body(m), "$.data")).toList());
    assertThat(second.stream().map(m -> field(m, "$.messageId")))
        .doesNotContainAnyElementsOf(first.stream().map(m -> field(m, "$.messageId")).toList());
    Map<String, Object> after = eventRow(catalog.cancelled());
    for (String column :
        List.of(
            "status", "title", "venue", "starts_at", "ends_at", "updated_at", "cancel_reason")) {
      assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
    }
    assertThat((Long) after.get("version"))
        .as("one write per replay")
        .isEqualTo((Long) before.get("version") + 2);
  }

  // ---- limit and cursor ----

  @Test
  void should_cover_every_event_exactly_once_when_it_is_paged_with_limit_and_next()
      throws Exception {
    Set<UUID> created = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      created.add(createPublished(organizer()));
    }
    drain();
    admin.purgeQueue(QUEUE);

    String after = null;
    int pages = 0;
    int total = 0;
    do {
      String request =
          after == null ? "{\"limit\":2}" : "{\"limit\":2,\"after\":\"" + after + "\"}";
      String response =
          replayAsAdmin(request)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      total += JsonPath.<Integer>read(response, "$.events");
      after =
          JsonPath.<Map<String, Object>>read(response, "$").containsKey("next")
              ? JsonPath.read(response, "$.next")
              : null;
      pages++;
    } while (after != null && pages < 10);
    drain();

    assertThat(total).isEqualTo(5);
    assertThat(pages).isEqualTo(3);
    List<String> replayedEvents = new ArrayList<>();
    for (Message message : receive(5)) {
      replayedEvents.add(eventIdOf(message));
    }
    assertThat(queueIsEmpty()).isTrue();
    assertThat(replayedEvents)
        .containsExactlyInAnyOrderElementsOf(created.stream().map(UUID::toString).toList());
  }

  // ---- access ----

  @Test
  void should_answer_401_when_there_is_no_token() throws Exception {
    mockMvc
        .perform(json(post(REPLAY), "{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void should_answer_403_when_the_caller_is_an_organizer() throws Exception {
    replay(organizer(), "{}").andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
  }

  @Test
  void should_answer_403_when_the_caller_has_no_role() throws Exception {
    replay(attendee(), "{}").andExpect(status().isForbidden());
  }

  @Test
  void should_not_let_an_admin_create_events_when_the_role_is_not_organizer() throws Exception {
    mockMvc
        .perform(json(withToken(post(EVENTS), adminToken()), eventJson("Rock Fest")))
        .andExpect(status().isForbidden());
  }

  // ---- bad requests ----

  @Test
  void should_answer_404_when_the_requested_event_does_not_exist() throws Exception {
    replayAsAdmin("{\"eventId\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "not-found"));
  }

  @Test
  void should_answer_400_when_the_limit_is_out_of_range() throws Exception {
    replayAsAdmin("{\"limit\":0}")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors[0].field").value("limit"));
    replayAsAdmin("{\"limit\":501}").andExpect(status().isBadRequest());
  }

  @Test
  void should_answer_400_when_the_event_id_is_not_a_uuid() throws Exception {
    replayAsAdmin("{\"eventId\":\"not-a-uuid\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // ---- concurrency ----

  @Test
  void should_count_a_conflict_and_replay_the_rest_when_an_event_changes_during_the_replay()
      throws Exception {
    UUID first = createPublished(organizer());
    UUID second = createPublished(organizer());
    drain();
    admin.purgeQueue(QUEUE);
    AtomicBoolean fired = new AtomicBoolean();
    // Another transaction commits a change to whichever event is loaded first.
    UUID[] touched = new UUID[1];
    repositoryHook.onAfterLoad(id -> {
      if (fired.compareAndSet(false, true)) {
        touched[0] = id;
        try {
          commitInAnotherTransaction("UPDATE event SET version = version + 1 WHERE id = ?", id);
        } catch (SQLException e) {
          throw new IllegalStateException("The competing transaction failed", e);
        }
      }
    });

    replayAsAdmin("{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events").value(1))
        .andExpect(jsonPath("$.conflicts").value(1));
    drain();

    UUID replayed = touched[0].equals(first) ? second : first;
    assertThat(eventIdOf(receive())).isEqualTo(replayed.toString());
    assertThat(queueIsEmpty()).as("the conflicting event wrote nothing").isTrue();
  }
}
