package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoutingKeysTest {

  /** The keys stated in the descriptions of the schemas in contracts/events. */
  @ParameterizedTest
  @CsvSource({
    "EventPublished, 1, catalog.event.published.v1",
    "EventCancelled, 1, catalog.event.cancelled.v1",
    "EventRescheduled, 1, catalog.event.rescheduled.v1",
    "EventPublished, 2, catalog.event.published.v2"
  })
  void should_follow_the_contract_convention_when_building_a_key(
      String eventType, int version, String expected) {
    assertThat(RoutingKeys.of("catalog", "Event", eventType, version)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"BookingConfirmed, booking.booking.confirmed.v1"})
  void should_use_the_service_and_aggregate_of_the_producer_when_they_differ(
      String eventType, String expected) {
    assertThat(RoutingKeys.of("booking", "Booking", eventType, 1)).isEqualTo(expected);
  }

  @org.junit.jupiter.api.Test
  void should_keep_the_whole_event_name_when_it_does_not_start_with_the_aggregate() {
    assertThat(RoutingKeys.of("catalog", "Event", "Archived", 1))
        .isEqualTo("catalog.event.archived.v1");
  }
}
