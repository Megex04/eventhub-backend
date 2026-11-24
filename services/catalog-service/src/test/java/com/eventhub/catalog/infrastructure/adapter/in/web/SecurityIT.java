package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.catalog.support.TestTokens;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** The access rules of the OpenAPI: which routes are public, who may write, how denial looks. */
class SecurityIT extends WebIntegrationTest {

  private static final String ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  /** Every non-public operation of the contract. The security filter answers before validation. */
  static Stream<Arguments> protectedOperations() {
    return Stream.of(
        Arguments.of(HttpMethod.POST, EVENTS),
        Arguments.of(HttpMethod.PUT, EVENTS + "/" + ID),
        Arguments.of(HttpMethod.DELETE, EVENTS + "/" + ID),
        Arguments.of(HttpMethod.POST, EVENTS + "/" + ID + "/zones"),
        Arguments.of(HttpMethod.PUT, EVENTS + "/" + ID + "/zones/" + ID),
        Arguments.of(HttpMethod.DELETE, EVENTS + "/" + ID + "/zones/" + ID),
        Arguments.of(HttpMethod.POST, EVENTS + "/" + ID + "/publish"),
        Arguments.of(HttpMethod.POST, EVENTS + "/" + ID + "/cancel"),
        Arguments.of(HttpMethod.POST, EVENTS + "/" + ID + "/reschedule"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protectedOperations")
  void should_answer_401_with_a_challenge_when_the_token_is_missing(HttpMethod method, String path)
      throws Exception {
    mockMvc
        .perform(json(request(method, path), "{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "unauthorized"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("protectedOperations")
  void should_answer_403_when_the_caller_lacks_the_organizer_role(HttpMethod method, String path)
      throws Exception {
    mockMvc
        .perform(json(withToken(request(method, path), attendee()), "{}"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEMS + "forbidden"))
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void should_not_change_anything_when_a_caller_without_the_role_tries_to_create()
      throws Exception {
    mockMvc
        .perform(
            json(withToken(request(HttpMethod.POST, EVENTS), attendee()), eventJson("Rock Fest")))
        .andExpect(status().isForbidden());

    Integer rows = jdbc.queryForObject("SELECT count(*) FROM event", Integer.class);
    assertThat(rows).isZero();
  }

  @Test
  void should_answer_401_when_the_token_has_expired() throws Exception {
    mockMvc
        .perform(withToken(get(EVENTS + "/" + UUID.randomUUID()), TestTokens.expired(ORGANIZER_ID)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value(PROBLEMS + "unauthorized"));
  }

  @Test
  void should_answer_401_when_the_token_signature_is_not_trusted() throws Exception {
    mockMvc
        .perform(
            withToken(
                json(request(HttpMethod.POST, EVENTS), eventJson("Rock Fest")),
                TestTokens.signedWithUntrustedKey(ORGANIZER_ID)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_answer_401_when_the_token_is_not_a_jwt() throws Exception {
    mockMvc
        .perform(withToken(get(EVENTS), "not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void should_answer_401_when_the_token_subject_is_not_a_uuid() throws Exception {
    mockMvc
        .perform(
            json(
                withToken(request(HttpMethod.POST, EVENTS), TestTokens.organizer("not-a-uuid")),
                eventJson("Rock Fest")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_serve_public_reads_without_a_token_when_the_contract_marks_them_open()
      throws Exception {
    mockMvc.perform(get(EVENTS)).andExpect(status().isOk());
    mockMvc.perform(get(EVENTS + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void should_serve_health_and_info_when_there_is_no_token() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }

  @Test
  void should_answer_401_when_an_unknown_route_is_requested_without_a_token() throws Exception {
    mockMvc.perform(get("/api/v1/unknown")).andExpect(status().isUnauthorized());
  }

  @Test
  void should_not_expose_the_stack_trace_when_a_request_fails() throws Exception {
    String body =
        mockMvc
            .perform(withToken(get(EVENTS + "/not-a-uuid"), organizer()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("Exception").doesNotContain("at com.eventhub");
  }
}
