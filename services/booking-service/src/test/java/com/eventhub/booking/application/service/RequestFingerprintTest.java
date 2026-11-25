package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.EVENT;
import static com.eventhub.booking.application.AppFixtures.GENERAL_ZONE;
import static com.eventhub.booking.application.AppFixtures.VIP_ZONE;
import static com.eventhub.booking.application.AppFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.domain.RequestedItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  private static final RequestedItem VIP_2 = new RequestedItem(VIP_ZONE, 2);
  private static final RequestedItem GENERAL_3 = new RequestedItem(GENERAL_ZONE, 3);

  @Test
  void should_not_change_when_the_items_come_in_another_order() {
    assertThat(RequestFingerprint.of(EVENT, List.of(VIP_2, GENERAL_3)))
        .isEqualTo(RequestFingerprint.of(EVENT, List.of(GENERAL_3, VIP_2)));
  }

  @Test
  void should_be_a_sha256_in_lowercase_hex() {
    assertThat(RequestFingerprint.of(EVENT, List.of(VIP_2))).matches("[0-9a-f]{64}");
  }

  @Test
  void should_be_stable_for_the_same_request() {
    assertThat(RequestFingerprint.of(EVENT, List.of(VIP_2)))
        .isEqualTo(RequestFingerprint.of(EVENT, List.of(VIP_2)));
  }

  @Test
  void should_change_when_a_quantity_changes() {
    assertThat(RequestFingerprint.of(EVENT, List.of(VIP_2)))
        .isNotEqualTo(RequestFingerprint.of(EVENT, List.of(new RequestedItem(VIP_ZONE, 3))));
  }

  @Test
  void should_change_when_a_zone_changes() {
    assertThat(RequestFingerprint.of(EVENT, List.of(VIP_2)))
        .isNotEqualTo(RequestFingerprint.of(EVENT, List.of(new RequestedItem(GENERAL_ZONE, 2))));
  }

  @Test
  void should_change_when_the_event_changes() {
    assertThat(RequestFingerprint.of(EVENT, List.of(VIP_2)))
        .isNotEqualTo(RequestFingerprint.of(uuid(999), List.of(VIP_2)));
  }

  @Test
  void should_not_confuse_one_item_with_two_when_the_quantities_add_up() {
    assertThat(RequestFingerprint.of(EVENT, List.of(new RequestedItem(VIP_ZONE, 3))))
        .isNotEqualTo(
            RequestFingerprint.of(
                EVENT, List.of(new RequestedItem(VIP_ZONE, 1), new RequestedItem(VIP_ZONE, 2))));
  }
}
