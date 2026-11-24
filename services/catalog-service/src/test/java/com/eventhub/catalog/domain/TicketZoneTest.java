package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketZoneTest {

  private static final UUID ID = UUID.randomUUID();

  @Test
  void should_create_zone_when_data_is_valid() {
    TicketZone zone = TicketZone.create(ID, "VIP", money("250.00"), 100);

    assertThat(zone.id()).isEqualTo(ID);
    assertThat(zone.name()).isEqualTo("VIP");
    assertThat(zone.price()).isEqualTo(money("250.00"));
    assertThat(zone.capacity()).isEqualTo(100);
  }

  @Test
  void should_strip_name_when_it_has_surrounding_whitespace() {
    assertThat(TicketZone.create(ID, "  VIP  ", money("1.00"), 1).name()).isEqualTo("VIP");
  }

  @Test
  void should_accept_name_when_it_has_maximum_length() {
    assertThat(TicketZone.create(ID, "a".repeat(60), money("1.00"), 1).name()).hasSize(60);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 61})
  void should_reject_name_when_length_is_out_of_bounds(int length) {
    assertThatThrownBy(() -> TicketZone.create(ID, "a".repeat(length), money("1.00"), 1))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("name"));
  }

  @Test
  void should_reject_name_when_it_is_blank() {
    assertThatThrownBy(() -> TicketZone.create(ID, "   ", money("1.00"), 1))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_name_when_it_is_null() {
    assertThatThrownBy(() -> TicketZone.create(ID, null, money("1.00"), 1))
        .isInstanceOf(InvalidValueException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 100_000})
  void should_accept_capacity_when_it_is_at_the_bounds(int capacity) {
    assertThat(TicketZone.create(ID, "VIP", money("1.00"), capacity).capacity())
        .isEqualTo(capacity);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 100_001})
  void should_reject_capacity_when_it_is_out_of_bounds(int capacity) {
    assertThatThrownBy(() -> TicketZone.create(ID, "VIP", money("1.00"), capacity))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("capacity"));
  }

  @Test
  void should_reject_zone_when_price_is_null() {
    assertThatThrownBy(() -> TicketZone.create(ID, "VIP", null, 10))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("price"));
  }

  @Test
  void should_reject_zone_when_id_is_null() {
    assertThatThrownBy(() -> TicketZone.create(null, "VIP", money("1.00"), 10))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_keep_identity_when_details_are_replaced() {
    TicketZone original = TicketZone.create(ID, "VIP", money("250.00"), 100);

    TicketZone changed = original.withDetails("Platinum", money("400.00"), 50);

    assertThat(changed.id()).isEqualTo(ID);
    assertThat(changed.name()).isEqualTo("Platinum");
    assertThat(original.name()).isEqualTo("VIP");
  }

  @Test
  void should_be_equal_when_ids_match_regardless_of_details() {
    TicketZone a = TicketZone.create(ID, "VIP", money("250.00"), 100);
    TicketZone b = TicketZone.create(ID, "Other", money("1.00"), 1);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  void should_not_be_equal_when_compared_with_other_ids_or_types() {
    TicketZone zone = TicketZone.create(ID, "VIP", money("1.00"), 1);

    assertThat(zone).isNotEqualTo(TicketZone.create(UUID.randomUUID(), "VIP", money("1.00"), 1));
    assertThat(zone).isNotEqualTo("VIP");
  }

  @Test
  void should_describe_itself_with_id_and_name_when_converted_to_string() {
    assertThat(TicketZone.create(ID, "VIP", money("1.00"), 1).toString())
        .contains(ID.toString())
        .contains("VIP");
  }

  @Test
  void should_match_name_when_case_and_surrounding_spaces_differ() {
    TicketZone zone = TicketZone.create(ID, "VIP", money("1.00"), 1);

    assertThat(zone.hasName("  vip ")).isTrue();
    assertThat(zone.hasName("General")).isFalse();
  }
}
