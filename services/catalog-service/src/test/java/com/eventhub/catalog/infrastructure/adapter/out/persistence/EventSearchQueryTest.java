package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.query.EventSort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class EventSearchQueryTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "plain text|plain text",
        "50%|50!%",
        "a_b|a!_b",
        "100!|100!!",
        "!%_|!!!%!_",
        "back\\slash|back\\slash",
        "%%|!%!%"
      })
  void should_escape_wildcards_and_the_escape_character_when_text_is_used_in_a_like_pattern(
      String input, String expected) {
    assertThat(EventSearchQuery.escapeLike(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(EventSort.class)
  void should_map_every_sort_to_a_fixed_order_by_clause_when_sort_is_requested(EventSort sort) {
    assertThat(EventSearchQuery.orderBy(sort))
        .matches("(lower\\(e\\.title\\)|e\\.startsAt) (asc|desc), e\\.id asc");
  }

  @Test
  void should_order_by_the_expected_column_and_direction_for_each_sort() {
    assertThat(EventSearchQuery.orderBy(EventSort.STARTS_AT_ASC)).startsWith("e.startsAt asc");
    assertThat(EventSearchQuery.orderBy(EventSort.STARTS_AT_DESC)).startsWith("e.startsAt desc");
    assertThat(EventSearchQuery.orderBy(EventSort.TITLE_ASC)).startsWith("lower(e.title) asc");
    assertThat(EventSearchQuery.orderBy(EventSort.TITLE_DESC)).startsWith("lower(e.title) desc");
  }

  @Test
  void should_always_break_ties_by_id_ascending_when_sort_is_descending() {
    assertThat(EventSearchQuery.orderBy(EventSort.STARTS_AT_DESC)).endsWith("e.id asc");
    assertThat(EventSearchQuery.orderBy(EventSort.TITLE_DESC)).endsWith("e.id asc");
  }
}
