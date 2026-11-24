package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ZonesControllerIT extends WebIntegrationTest {

  private static String zones(UUID eventId) {
    return EVENTS + "/" + eventId + "/zones";
  }

  // ---- POST /events/{id}/zones ----

  @Test
  void should_create_the_zone_when_an_organizer_adds_it_to_their_draft() throws Exception {
    UUID id = createDraft(organizer());

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), zoneJson("VIP", "150.00")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.name").value("VIP"))
        .andExpect(jsonPath("$.price.amount").value("150.00"))
        .andExpect(jsonPath("$.price.currency").value("PEN"))
        .andExpect(jsonPath("$.capacity").value(100));

    mockMvc
        .perform(withToken(get(EVENTS + "/" + id), organizer()))
        .andExpect(jsonPath("$.zones", hasSize(1)));
  }

  @Test
  void should_answer_422_when_the_zone_name_already_exists_in_the_event() throws Exception {
    UUID id = createDraftWithZone(organizer());

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), zoneJson("general", "80.00")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "duplicate-zone-name"));
  }

  @Test
  void should_answer_422_when_the_event_already_has_twenty_zones() throws Exception {
    String twentyZones =
        IntStream.range(0, 20)
            .mapToObj(i ->
                "{\"name\":\"Zone %d\",\"price\":{\"amount\":\"10.00\",\"currency\":\"PEN\"},\"capacity\":10}"
                    .formatted(i))
            .collect(Collectors.joining(",", "[", "]"));
    String body =
        """
        {"title":"Rock Fest","venue":"Estadio Nacional","startsAt":"%s","endsAt":"%s","zones":%s}
        """
            .formatted(inDays(30), inDays(31), twentyZones);
    UUID id = createEvent(organizer(), body);

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), zoneJson("One too many", "10.00")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "zone-limit-exceeded"));
  }

  @Test
  void should_answer_409_when_adding_a_zone_to_a_published_event() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), zoneJson("VIP", "150.00")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-state"));
  }

  @Test
  void should_answer_403_when_another_organizer_adds_a_zone() throws Exception {
    UUID id = createPublished(organizer());

    mockMvc
        .perform(json(withToken(post(zones(id)), otherOrganizer()), zoneJson("VIP", "150.00")))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_answer_404_when_adding_a_zone_to_an_event_that_does_not_exist() throws Exception {
    mockMvc
        .perform(
            json(withToken(post(zones(UUID.randomUUID())), organizer()), zoneJson("VIP", "150.00")))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_answer_400_when_the_capacity_is_zero() throws Exception {
    UUID id = createDraft(organizer());
    String body =
        "{\"name\":\"VIP\",\"price\":{\"amount\":\"10.00\",\"currency\":\"PEN\"},\"capacity\":0}";

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("capacity"));
  }

  @Test
  void should_answer_400_when_the_currency_is_not_an_iso_code() throws Exception {
    UUID id = createDraft(organizer());
    String body =
        "{\"name\":\"VIP\",\"price\":{\"amount\":\"10.00\",\"currency\":\"pen\"},\"capacity\":10}";

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("price.currency"));
  }

  @Test
  void should_answer_400_when_the_currency_matches_the_pattern_but_is_not_iso_4217()
      throws Exception {
    UUID id = createDraft(organizer());
    String body =
        "{\"name\":\"VIP\",\"price\":{\"amount\":\"10.00\",\"currency\":\"ZZZ\"},\"capacity\":10}";

    mockMvc
        .perform(json(withToken(post(zones(id)), organizer()), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("currency"));
  }

  // ---- PUT /events/{id}/zones/{zoneId} ----

  @Test
  void should_update_the_zone_when_the_organizer_sends_new_values() throws Exception {
    UUID id = createDraft(organizer());
    UUID zoneId = addZone(organizer(), id, "General", "100.00");

    mockMvc
        .perform(
            json(
                withToken(put(zones(id) + "/" + zoneId), organizer()), zoneJson("Platea", "75.50")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(zoneId.toString()))
        .andExpect(jsonPath("$.name").value("Platea"))
        .andExpect(jsonPath("$.price.amount").value("75.50"));
  }

  @Test
  void should_answer_404_when_updating_a_zone_that_is_not_in_the_event() throws Exception {
    UUID id = createDraftWithZone(organizer());

    mockMvc
        .perform(
            json(
                withToken(put(zones(id) + "/" + UUID.randomUUID()), organizer()),
                zoneJson("Platea", "75.50")))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_answer_409_when_updating_a_zone_of_a_published_event() throws Exception {
    UUID id = createDraft(organizer());
    UUID zoneId = addZone(organizer(), id, "General", "100.00");
    mockMvc
        .perform(withToken(post(EVENTS + "/" + id + "/publish"), organizer()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            json(
                withToken(put(zones(id) + "/" + zoneId), organizer()), zoneJson("Platea", "75.50")))
        .andExpect(status().isConflict());
  }

  @Test
  void should_answer_404_when_another_organizer_updates_a_zone_of_a_draft() throws Exception {
    UUID id = createDraft(organizer());
    UUID zoneId = addZone(organizer(), id, "General", "100.00");

    mockMvc
        .perform(
            json(
                withToken(put(zones(id) + "/" + zoneId), otherOrganizer()),
                zoneJson("Platea", "75.50")))
        .andExpect(status().isNotFound());
  }

  // ---- DELETE /events/{id}/zones/{zoneId} ----

  @Test
  void should_remove_the_zone_when_the_organizer_deletes_it() throws Exception {
    UUID id = createDraft(organizer());
    UUID zoneId = addZone(organizer(), id, "General", "100.00");

    mockMvc
        .perform(withToken(delete(zones(id) + "/" + zoneId), organizer()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(withToken(get(EVENTS + "/" + id), organizer()))
        .andExpect(jsonPath("$.zones", hasSize(0)));
  }

  @Test
  void should_answer_404_when_removing_a_zone_that_is_not_in_the_event() throws Exception {
    UUID id = createDraftWithZone(organizer());

    mockMvc
        .perform(withToken(delete(zones(id) + "/" + UUID.randomUUID()), organizer()))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_answer_409_when_removing_a_zone_of_a_published_event() throws Exception {
    UUID id = createDraft(organizer());
    UUID zoneId = addZone(organizer(), id, "General", "100.00");
    mockMvc
        .perform(withToken(post(EVENTS + "/" + id + "/publish"), organizer()))
        .andExpect(status().isOk());

    mockMvc
        .perform(withToken(delete(zones(id) + "/" + zoneId), organizer()))
        .andExpect(status().isConflict());
  }

  @Test
  void should_answer_404_when_another_organizer_removes_a_zone_of_a_draft() throws Exception {
    UUID id = createDraft(organizer());
    UUID zoneId = addZone(organizer(), id, "General", "100.00");

    mockMvc
        .perform(withToken(delete(zones(id) + "/" + zoneId), otherOrganizer()))
        .andExpect(status().isNotFound());
  }
}
