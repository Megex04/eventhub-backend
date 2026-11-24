package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.catalog.support.PostgresIntegrationTest;
import com.eventhub.catalog.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the whole service through HTTP against a real PostgreSQL and real signed JWTs. Helpers
 * arrange events by calling the API itself, so arranging never bypasses the rules under test.
 */
@AutoConfigureMockMvc
public abstract class WebIntegrationTest extends PostgresIntegrationTest {

  protected static final String EVENTS = "/api/v1/events";
  protected static final String PROBLEM_JSON = "application/problem+json";
  protected static final String PROBLEMS = "https://eventhub.dev/problems/";

  protected static final String ORGANIZER_ID = "3f1c2a54-8b7e-4d1a-9c52-6a0f4b7d1e01";
  protected static final String OTHER_ORGANIZER_ID = "5b2e9c1a-7d3f-4e86-a1b4-0c9d8e7f6a52";
  protected static final String ATTENDEE_ID = "9a7d5e12-3c4b-4f60-8e21-b5d0c3a96f02";

  @Autowired protected MockMvc mockMvc;

  protected static String organizer() {
    return TestTokens.organizer(ORGANIZER_ID);
  }

  protected static String otherOrganizer() {
    return TestTokens.organizer(OTHER_ORGANIZER_ID);
  }

  protected static String attendee() {
    return TestTokens.attendee(ATTENDEE_ID);
  }

  protected static MockHttpServletRequestBuilder withToken(
      MockHttpServletRequestBuilder request, String token) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }

  protected static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  protected static Instant inDays(long days) {
    return Instant.now().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
  }

  protected static String eventJson(String title, Instant startsAt, Instant endsAt) {
    return """
    {"title":"%s","description":"Open air","venue":"Estadio Nacional","startsAt":"%s","endsAt":"%s"}
    """
        .formatted(title, startsAt, endsAt);
  }

  protected static String eventJson(String title) {
    return eventJson(title, inDays(30), inDays(30).plus(Duration.ofHours(4)));
  }

  protected static String zoneJson(String name, String amount) {
    return """
    {"name":"%s","price":{"amount":"%s","currency":"PEN"},"capacity":100}
    """
        .formatted(name, amount);
  }

  protected UUID createDraft(String token) throws Exception {
    return createEvent(token, eventJson("Rock Fest"));
  }

  protected UUID createEvent(String token, String body) throws Exception {
    String response =
        mockMvc
            .perform(json(withToken(post(EVENTS), token), body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(response, "$.id"));
  }

  protected UUID createDraftWithZone(String token) throws Exception {
    UUID id = createDraft(token);
    addZone(token, id, "General", "100.00");
    return id;
  }

  protected UUID addZone(String token, UUID eventId, String name, String amount) throws Exception {
    String response =
        mockMvc
            .perform(
                json(
                    withToken(post(EVENTS + "/" + eventId + "/zones"), token),
                    zoneJson(name, amount)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(response, "$.id"));
  }

  protected UUID createPublished(String token) throws Exception {
    UUID id = createDraftWithZone(token);
    mockMvc
        .perform(withToken(post(EVENTS + "/" + id + "/publish"), token))
        .andExpect(status().isOk());
    return id;
  }
}
