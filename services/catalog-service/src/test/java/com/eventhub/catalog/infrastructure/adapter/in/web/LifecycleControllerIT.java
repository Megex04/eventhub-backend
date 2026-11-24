package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LifecycleControllerIT extends WebIntegrationTest {

  private static String path(UUID eventId, String action) {
    return EVENTS + "/" + eventId + "/" + action;
  }

  private int outboxRows(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, eventId);
  }

  // ---- POST /events/{id}/publish ----

  @Test
  void should_publish_the_event_and_record_it_in_the_outbox_when_the_draft_is_complete()
      throws Exception {
    UUID id = createDraftWithZone(organizer());

    mockMvc
        .perform(withToken(post(path(id, "publish")), organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    assertThat(outboxRows(id)).isEqualTo(1);
  }

  @Test
  void should_answer_422_when_publishing_an_event_without_zones() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(withToken(post(path(id, "publish")), organizer()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "event-without-zones"));

    assertThat(outboxRows(id)).isZero();
  }

  @Test
  void should_answer_422_when_publishing_an_event_that_starts_in_the_past() throws Exception {
    Instant startsAt = Instant.now().minus(Duration.ofDays(2));
    UUID id =
        createEvent(
            organizer(), eventJson("Old Fest", startsAt, startsAt.plus(Duration.ofHours(4))));
    addZone(organizer(), id, "General", "100.00");

    mockMvc
        .perform(withToken(post(path(id, "publish")), organizer()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "starts-at-not-in-future"));
  }

  @Test
  void should_answer_409_when_publishing_an_event_twice() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(withToken(post(path(id, "publish")), organizer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-state"));

    assertThat(outboxRows(id)).isEqualTo(1);
  }

  @Test
  void should_answer_404_when_another_organizer_publishes_a_draft() throws Exception {
    UUID id = createDraftWithZone(organizer());

    mockMvc
        .perform(withToken(post(path(id, "publish")), otherOrganizer()))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_answer_404_when_publishing_an_event_that_does_not_exist() throws Exception {
    mockMvc
        .perform(withToken(post(path(UUID.randomUUID(), "publish")), organizer()))
        .andExpect(status().isNotFound());
  }

  // ---- POST /events/{id}/cancel ----

  @Test
  void should_cancel_the_event_and_expose_the_reason_when_it_is_published() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "cancel")), organizer()),
                "{\"reason\":\"Venue unavailable\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelReason").value("Venue unavailable"));

    assertThat(outboxRows(id)).isEqualTo(2);
  }

  @Test
  void should_answer_409_when_cancelling_an_event_that_is_still_a_draft() throws Exception {
    UUID id = createDraftWithZone(organizer());

    mockMvc
        .perform(
            json(withToken(post(path(id, "cancel")), organizer()), "{\"reason\":\"Changed mind\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void should_answer_409_when_cancelling_an_event_twice() throws Exception {
    UUID id = createPublished(organizer());
    String body = "{\"reason\":\"Venue unavailable\"}";
    mockMvc
        .perform(json(withToken(post(path(id, "cancel")), organizer()), body))
        .andExpect(status().isOk());

    mockMvc
        .perform(json(withToken(post(path(id, "cancel")), organizer()), body))
        .andExpect(status().isConflict());
  }

  @Test
  void should_answer_400_when_the_cancel_reason_is_too_short() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(json(withToken(post(path(id, "cancel")), organizer()), "{\"reason\":\"no\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("reason"));
  }

  @Test
  void should_answer_400_when_the_cancel_body_has_no_reason() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(json(withToken(post(path(id, "cancel")), organizer()), "{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_answer_403_when_another_organizer_cancels_the_event() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "cancel")), otherOrganizer()),
                "{\"reason\":\"Venue unavailable\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_answer_404_when_cancelling_an_event_that_does_not_exist() throws Exception {
    mockMvc
        .perform(
            json(
                withToken(post(path(UUID.randomUUID(), "cancel")), organizer()),
                "{\"reason\":\"Venue unavailable\"}"))
        .andExpect(status().isNotFound());
  }

  // ---- POST /events/{id}/reschedule ----

  @Test
  void should_move_the_event_and_keep_the_venue_when_reschedule_omits_it() throws Exception {
    UUID id = createPublished(organizer());
    Instant newStart = inDays(45);

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(newStart, newStart.plus(Duration.ofHours(3)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.startsAt").value(newStart.toString()))
        .andExpect(jsonPath("$.venue").value("Estadio Nacional"));

    assertThat(outboxRows(id)).isEqualTo(2);
  }

  @Test
  void should_change_the_venue_when_reschedule_provides_one() throws Exception {
    UUID id = createPublished(organizer());
    Instant newStart = inDays(45);

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"venue\":\"Arena Lima\"}"
                    .formatted(newStart, newStart.plus(Duration.ofHours(3)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.venue").value("Arena Lima"));
  }

  @Test
  void should_answer_409_when_rescheduling_a_draft() throws Exception {
    UUID id = createDraftWithZone(organizer());
    Instant newStart = inDays(45);

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(newStart, newStart.plus(Duration.ofHours(3)))))
        .andExpect(status().isConflict());
  }

  @Test
  void should_answer_422_when_rescheduling_an_event_that_has_already_started() throws Exception {
    UUID id = createPublished(organizer());
    clock.advance(Duration.ofDays(31));
    Instant newStart = Instant.now().plus(Duration.ofDays(60));

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(newStart, newStart.plus(Duration.ofHours(3)))))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "event-already-started"));
  }

  @Test
  void should_answer_422_when_the_new_start_is_in_the_past() throws Exception {
    UUID id = createPublished(organizer());
    Instant past = Instant.now().minus(Duration.ofDays(1));

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(past, past.plus(Duration.ofHours(3)))))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "starts-at-not-in-future"));
  }

  @Test
  void should_answer_400_when_the_new_end_is_before_the_new_start() throws Exception {
    UUID id = createPublished(organizer());
    Instant newStart = inDays(45);

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(newStart, newStart.minus(Duration.ofHours(1)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("endsAt"));
  }

  @Test
  void should_answer_403_when_another_organizer_reschedules_the_event() throws Exception {
    UUID id = createPublished(organizer());
    Instant newStart = inDays(45);

    mockMvc
        .perform(
            json(
                withToken(post(path(id, "reschedule")), otherOrganizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(newStart, newStart.plus(Duration.ofHours(3)))))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_answer_404_when_rescheduling_an_event_that_does_not_exist() throws Exception {
    Instant newStart = inDays(45);

    mockMvc
        .perform(
            json(
                withToken(post(path(UUID.randomUUID(), "reschedule")), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(newStart, newStart.plus(Duration.ofHours(3)))))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_show_the_cancelled_status_to_the_public_when_the_event_was_cancelled()
      throws Exception {
    UUID id = createPublished(organizer());
    mockMvc
        .perform(
            json(
                withToken(post(path(id, "cancel")), organizer()),
                "{\"reason\":\"Venue unavailable\"}"))
        .andExpect(status().isOk());

    // Not PUBLISHED any more, so it leaves the public catalog and only its organizer can see it.
    mockMvc.perform(get(EVENTS + "/" + id)).andExpect(status().isNotFound());
    mockMvc
        .perform(withToken(get(EVENTS + "/" + id), organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }
}
