package com.eventhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ZoneInventoryTest {

  private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
  private static final UUID ZONE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

  private static ZoneInventory inventory(int capacity, int reserved) {
    return ZoneInventory.restore(EVENT, ZONE, capacity, reserved, 0);
  }

  @Test
  void should_start_with_nothing_reserved_when_created() {
    ZoneInventory created = ZoneInventory.create(EVENT, ZONE, 20);

    assertThat(created.reserved()).isZero();
    assertThat(created.available()).isEqualTo(20);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void should_reject_a_capacity_below_one_when_created(int capacity) {
    assertThatThrownBy(() -> ZoneInventory.create(EVENT, ZONE, capacity))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "capacity");
  }

  @Test
  void should_reserve_up_to_the_capacity() {
    ZoneInventory zone = inventory(10, 7);

    zone.reserve(3);

    assertThat(zone.reserved()).isEqualTo(10);
    assertThat(zone.available()).isZero();
  }

  @Test
  void should_refuse_to_reserve_one_ticket_beyond_the_capacity() {
    ZoneInventory zone = inventory(10, 7);

    assertThatThrownBy(() -> zone.reserve(4))
        .isInstanceOfSatisfying(
            InsufficientInventoryException.class,
            e -> {
              assertThat(e.zoneId()).isEqualTo(ZONE);
              assertThat(e.requested()).isEqualTo(4);
              assertThat(e.available()).isEqualTo(3);
            });
    assertThat(zone.reserved()).isEqualTo(7);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -3})
  void should_reject_a_reservation_below_one_ticket(int quantity) {
    ZoneInventory zone = inventory(10, 0);

    assertThatThrownBy(() -> zone.reserve(quantity)).isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_release_down_to_zero() {
    ZoneInventory zone = inventory(10, 4);

    zone.release(4);

    assertThat(zone.reserved()).isZero();
  }

  @Test
  void should_never_release_more_than_what_is_reserved() {
    ZoneInventory zone = inventory(10, 2);

    assertThatThrownBy(() -> zone.release(3)).isInstanceOf(InventoryInvariantException.class);
    assertThat(zone.reserved()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void should_reject_a_release_below_one_ticket(int quantity) {
    ZoneInventory zone = inventory(10, 5);

    assertThatThrownBy(() -> zone.release(quantity)).isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_keep_the_stored_version_when_the_state_changes() {
    ZoneInventory zone = ZoneInventory.restore(EVENT, ZONE, 10, 0, 7);

    zone.reserve(1);

    assertThat(zone.version()).isEqualTo(7);
  }
}
