package com.eventhub.contracttesting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventSchemasTest {

  private static final String ID = "3f1c2a54-8b7e-4d1a-9c52-6a0f4b7d1e01";

  private static String bookingExpired(String producer) {
    return """
    {"messageId":"%1$s","type":"BookingExpired","version":1,
     "occurredAt":"2026-11-25T10:00:00Z","correlationId":"%1$s","producer":"%2$s",
     "data":{"bookingId":"%1$s","userId":"%1$s","eventId":"%1$s",
             "expiredAt":"2026-11-25T10:00:00Z"}}
    """
        .formatted(ID, producer);
  }

  @Test
  void should_accept_a_booking_event_when_it_matches_its_schema() {
    assertThat(EventSchemas.violations("BookingExpired", bookingExpired("booking-service")))
        .isEmpty();
  }

  @Test
  void should_report_violations_when_the_producer_is_not_the_one_in_the_schema() {
    assertThat(EventSchemas.violations("BookingExpired", bookingExpired("catalog-service")))
        .isNotEmpty();
  }

  @Test
  void should_report_violations_when_a_catalog_event_is_empty() {
    assertThat(EventSchemas.violations("EventPublished", "{}")).isNotEmpty();
  }

  @Test
  void should_resolve_a_multi_word_type_when_it_has_a_schema() {
    assertThat(EventSchemas.violations("BookingPaymentRefunded", "{}")).isNotEmpty();
  }

  @Test
  void should_fail_when_the_event_type_has_no_schema() {
    assertThatThrownBy(() -> EventSchemas.violations("NoSuchEvent", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchEvent");
  }
}
