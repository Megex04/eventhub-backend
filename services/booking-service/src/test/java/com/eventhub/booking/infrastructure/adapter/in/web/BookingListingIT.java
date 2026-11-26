package com.eventhub.booking.infrastructure.adapter.in.web;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** "My bookings": only the caller's, filtered and paged by the database. */
class BookingListingIT extends ApiIT {

  private final List<UUID> alices = new ArrayList<>();
  private final List<UUID> bobs = new ArrayList<>();

  @BeforeEach
  void book() throws Exception {
    for (int i = 0; i < 5; i++) {
      alices.add(booked(alice, 1));
      clock.advance(Duration.ofSeconds(1));
    }
    for (int i = 0; i < 3; i++) {
      bobs.add(booked(bob, 1));
      clock.advance(Duration.ofSeconds(1));
    }
    cancel(alice, alices.get(1)).andExpect(status().isOk());
  }

  private static List<String> ids(String body) {
    return JsonPath.read(body, "$.content[*].id");
  }

  private List<String> idsOf(String token, String query) throws Exception {
    return ids(
        list(token, query)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void should_list_only_the_bookings_of_the_caller_newest_first() throws Exception {
    list(alice, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20));

    List<String> expected = new ArrayList<>();
    for (int i = alices.size() - 1; i >= 0; i--) {
      expected.add(alices.get(i).toString());
    }
    assertThat(idsOf(alice, "")).containsExactlyElementsOf(expected);
    assertThat(idsOf(alice, ""))
        .doesNotContainAnyElementsOf(bobs.stream().map(UUID::toString).toList());
  }

  @Test
  void should_show_each_customer_only_their_own_bookings() throws Exception {
    assertThat(idsOf(bob, ""))
        .hasSize(3)
        .containsExactlyInAnyOrderElementsOf(bobs.stream().map(UUID::toString).toList());
    list(bob, "").andExpect(jsonPath("$.totalElements").value(3));
  }

  @Test
  void should_page_the_results_with_the_right_totals() throws Exception {
    list(alice, "?page=1&size=2")
        .andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(jsonPath("$.totalPages").value(3))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(2));

    assertThat(idsOf(alice, "?page=1&size=2"))
        .containsExactly(alices.get(2).toString(), alices.get(1).toString());
    assertThat(idsOf(alice, "?page=2&size=2")).containsExactly(alices.get(0).toString());
    assertThat(idsOf(alice, "?page=3&size=2")).isEmpty();
  }

  @Test
  void should_sort_oldest_first_when_asked() throws Exception {
    assertThat(idsOf(alice, "?sort=createdAt"))
        .containsExactly(alices.stream().map(UUID::toString).toList().toArray(String[]::new));
  }

  @Test
  void should_filter_by_status() throws Exception {
    assertThat(idsOf(alice, "?status=CANCELLED")).containsExactly(alices.get(1).toString());
    list(alice, "?status=CANCELLED").andExpect(jsonPath("$.totalElements").value(1));
    list(alice, "?status=PENDING").andExpect(jsonPath("$.totalElements").value(4));
    list(alice, "?status=CONFIRMED").andExpect(jsonPath("$.totalElements").value(0));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"?size=0", "?size=101", "?page=-1", "?sort=bogus", "?status=PAID", "?size=abc"})
  void should_answer_400_when_a_query_parameter_is_invalid(String query) throws Exception {
    list(alice, query)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM));
  }

  @Test
  void should_accept_the_largest_allowed_page_size() throws Exception {
    list(alice, "?size=100").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
  }

  @Test
  void should_require_a_token_to_list() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BOOKINGS))
        .andExpect(status().isUnauthorized());
  }
}
