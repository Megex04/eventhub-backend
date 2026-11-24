package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

class EventsControllerIT extends WebIntegrationTest {

  // ---- POST /events ----

  @Test
  void should_create_a_draft_and_point_to_it_when_an_organizer_posts_a_valid_event()
      throws Exception {
    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), eventJson("Rock Fest")))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, containsString(EVENTS + "/")))
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.title").value("Rock Fest"))
        .andExpect(jsonPath("$.zones", hasSize(0)))
        .andExpect(jsonPath("$.cancelReason").doesNotExist());
  }

  @Test
  void should_take_the_organizer_from_the_token_when_the_body_names_someone_else()
      throws Exception {
    String body =
        """
        {"organizerId":"%s","title":"Rock Fest","venue":"Estadio Nacional","startsAt":"%s","endsAt":"%s"}
        """
            .formatted(OTHER_ORGANIZER_ID, inDays(30), inDays(31));

    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.organizerId").value(ORGANIZER_ID));
  }

  @Test
  void should_create_the_zones_with_the_event_when_the_request_includes_them() throws Exception {
    String body =
        """
        {"title":"Rock Fest","venue":"Estadio Nacional","startsAt":"%s","endsAt":"%s",
         "zones":[{"name":"VIP","price":{"amount":"150.00","currency":"PEN"},"capacity":50}]}
        """
            .formatted(inDays(30), inDays(31));

    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.zones", hasSize(1)))
        .andExpect(jsonPath("$.zones[0].id").isNotEmpty())
        .andExpect(jsonPath("$.zones[0].price.amount").value("150.00"));
  }

  @Test
  void should_answer_400_naming_the_field_when_the_title_is_too_short() throws Exception {
    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), eventJson("ab")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-error"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].field").value("title"))
        .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
  }

  @Test
  void should_answer_400_with_the_nested_path_when_a_zone_amount_has_one_decimal()
      throws Exception {
    String body =
        """
        {"title":"Rock Fest","venue":"Estadio Nacional","startsAt":"%s","endsAt":"%s",
         "zones":[{"name":"VIP","price":{"amount":"12.5","currency":"PEN"},"capacity":50}]}
        """
            .formatted(inDays(30), inDays(31));

    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("zones[0].price.amount"));
  }

  @Test
  void should_answer_400_when_a_required_field_is_missing() throws Exception {
    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), "{\"title\":\"Rock Fest\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[*].field")
                .value(org.hamcrest.Matchers.hasItems("venue", "startsAt", "endsAt")));
  }

  @Test
  void should_answer_400_without_leaking_parser_details_when_the_body_is_not_json()
      throws Exception {
    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), "{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-error"))
        .andExpect(jsonPath("$.detail").value(not(containsString("Jackson"))));
  }

  @Test
  void should_answer_400_when_the_event_ends_before_it_starts() throws Exception {
    String body = eventJson("Rock Fest", inDays(31), inDays(30));

    mockMvc
        .perform(json(withToken(post(EVENTS), organizer()), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("endsAt"));
  }

  // ---- GET /events/{id} ----

  @Test
  void should_return_the_draft_to_its_organizer_when_requesting_it() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(withToken(get(EVENTS + "/" + id), organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void should_answer_404_when_another_organizer_requests_a_draft() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(withToken(get(EVENTS + "/" + id), otherOrganizer()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "not-found"));
  }

  @Test
  void should_answer_404_when_an_anonymous_caller_requests_a_draft() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc.perform(get(EVENTS + "/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void should_return_a_published_event_without_a_token_when_requesting_it() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(get(EVENTS + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.zones", hasSize(1)));
  }

  @Test
  void should_answer_404_when_the_event_does_not_exist() throws Exception {
    mockMvc
        .perform(get(EVENTS + "/" + UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void should_answer_400_when_the_event_id_is_not_a_uuid() throws Exception {
    mockMvc
        .perform(get(EVENTS + "/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value("eventId"));
  }

  // ---- GET /events ----

  @Test
  void should_list_only_published_events_when_the_caller_is_anonymous() throws Exception {
    createDraft(organizer());
    UUID published = createPublished(organizer());

    mockMvc
        .perform(get(EVENTS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].id").value(published.toString()))
        .andExpect(jsonPath("$.content[0].zones").doesNotExist())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20));
  }

  @Test
  void should_include_own_drafts_when_listing_with_mine_and_a_token() throws Exception {
    UUID draft = createDraft(organizer());
    createDraft(otherOrganizer());

    mockMvc
        .perform(withToken(get(EVENTS).param("mine", "true"), organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].id").value(draft.toString()));
  }

  @Test
  void should_answer_401_when_listing_mine_without_a_token() throws Exception {
    mockMvc
        .perform(get(EVENTS).param("mine", "true"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "unauthorized"));
  }

  @Test
  void should_filter_by_status_when_an_organizer_lists_their_events() throws Exception {
    createDraft(organizer());
    UUID published = createPublished(organizer());

    mockMvc
        .perform(
            withToken(get(EVENTS).param("mine", "true").param("status", "PUBLISHED"), organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].id").value(published.toString()));
  }

  @Test
  void should_filter_by_text_when_q_matches_the_title() throws Exception {
    createEvent(organizer(), eventJson("Jazz Night"));
    createEvent(organizer(), eventJson("Rock Fest"));

    mockMvc
        .perform(withToken(get(EVENTS).param("mine", "true").param("q", "jazz"), organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].title").value("Jazz Night"));
  }

  @Test
  void should_filter_by_start_date_when_from_and_to_are_given() throws Exception {
    createEvent(organizer(), eventJson("Soon", inDays(5), inDays(6)));
    createEvent(organizer(), eventJson("Later", inDays(60), inDays(61)));

    mockMvc
        .perform(
            withToken(
                get(EVENTS)
                    .param("mine", "true")
                    .param("from", inDays(40).toString())
                    .param("to", inDays(70).toString()),
                organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].title").value("Later"));
  }

  @Test
  void should_paginate_and_sort_when_page_size_and_sort_are_given() throws Exception {
    createEvent(organizer(), eventJson("Alpha"));
    createEvent(organizer(), eventJson("Bravo"));
    createEvent(organizer(), eventJson("Charlie"));

    mockMvc
        .perform(
            withToken(
                get(EVENTS)
                    .param("mine", "true")
                    .param("sort", "-title")
                    .param("page", "1")
                    .param("size", "2"),
                organizer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].title").value("Alpha"))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(2));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"size=0", "size=101", "page=-1", "sort=price", "status=BOGUS", "from=yesterday"})
  void should_answer_400_when_a_list_parameter_is_out_of_contract(String parameter)
      throws Exception {
    String[] pair = parameter.split("=");

    mockMvc
        .perform(get(EVENTS).param(pair[0], pair[1]))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value(pair[0]));
  }

  // ---- PUT /events/{id} ----

  @Test
  void should_update_the_draft_when_the_organizer_sends_new_data() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(json(withToken(put(EVENTS + "/" + id), organizer()), eventJson("Renamed Fest")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Renamed Fest"))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void should_answer_409_when_updating_a_published_event() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(json(withToken(put(EVENTS + "/" + id), organizer()), eventJson("Renamed Fest")))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-state"));
  }

  @Test
  void should_answer_403_when_another_organizer_updates_the_event() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(json(withToken(put(EVENTS + "/" + id), otherOrganizer()), eventJson("Hijacked")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "forbidden"));
  }

  @Test
  void should_answer_404_when_updating_an_event_that_does_not_exist() throws Exception {
    mockMvc
        .perform(
            json(
                withToken(put(EVENTS + "/" + UUID.randomUUID()), organizer()),
                eventJson("Rock Fest")))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_answer_400_when_the_update_body_is_invalid() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(json(withToken(put(EVENTS + "/" + id), organizer()), eventJson("ab")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("title"));
  }

  // ---- DELETE /events/{id} ----

  @Test
  void should_delete_the_draft_when_the_organizer_requests_it() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(withToken(delete(EVENTS + "/" + id), organizer()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(withToken(get(EVENTS + "/" + id), organizer()))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_answer_409_when_deleting_a_published_event() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(withToken(delete(EVENTS + "/" + id), organizer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-state"));
  }

  @Test
  void should_answer_403_when_another_organizer_deletes_the_event() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(withToken(delete(EVENTS + "/" + id), otherOrganizer()))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_answer_404_when_deleting_an_event_that_does_not_exist() throws Exception {
    mockMvc
        .perform(withToken(delete(EVENTS + "/" + UUID.randomUUID()), organizer()))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_keep_the_event_untouched_when_a_delete_is_rejected() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(withToken(delete(EVENTS + "/" + id), organizer()))
        .andExpect(status().isConflict());

    Integer rows =
        jdbc.queryForObject("SELECT count(*) FROM event WHERE id = ?", Integer.class, id);
    assertThat(rows).isEqualTo(1);
  }
}
