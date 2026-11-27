package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoutingKeysTest {

  private static final Path SCHEMAS =
      Path.of("../../contracts/events").toAbsolutePath().normalize();
  private static final Pattern ROUTING_KEY = Pattern.compile("Routing key: `([^`]+)`");

  static Stream<Arguments> events() {
    return Stream.of(
        Arguments.of(
            "BookingCreated", "booking.booking.created.v1", "booking-created.v1.schema.json"),
        Arguments.of(
            "BookingConfirmed", "booking.booking.confirmed.v1", "booking-confirmed.v1.schema.json"),
        Arguments.of(
            "BookingExpired", "booking.booking.expired.v1", "booking-expired.v1.schema.json"),
        Arguments.of(
            "BookingCancelled", "booking.booking.cancelled.v1", "booking-cancelled.v1.schema.json"),
        Arguments.of(
            "BookingPaymentRefunded",
            "booking.booking.payment-refunded.v1",
            "booking-payment-refunded.v1.schema.json"));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("events")
  void should_build_the_key_the_convention_names_when_given_a_booking_event(
      String eventType, String expected, String schemaFile) {
    assertThat(RoutingKeys.of("booking", "Booking", eventType, 1)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} is the key its schema declares")
  @MethodSource("events")
  void should_match_the_routing_key_declared_in_the_contract_when_given_a_booking_event(
      String eventType, String expected, String schemaFile) throws Exception {
    String schema = Files.readString(SCHEMAS.resolve(schemaFile));
    Matcher declared = ROUTING_KEY.matcher(schema);

    assertThat(declared.find()).as("%s declares a routing key", schemaFile).isTrue();
    assertThat(RoutingKeys.of("booking", "Booking", eventType, 1)).isEqualTo(declared.group(1));
  }

  @ParameterizedTest
  @MethodSource("events")
  void should_carry_the_version_in_the_key_when_the_event_version_changes(
      String eventType, String expected, String schemaFile) {
    assertThat(RoutingKeys.of("booking", "Booking", eventType, 2)).endsWith(".v2");
  }
}
