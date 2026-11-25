package com.eventhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Each domain event maps 1:1 to a {@code booking-*.v1} schema: the record carries exactly the
 * properties of the schema's {@code data}. {@code occurredAt} is the envelope's own timestamp; for
 * the events whose schema names that instant ({@code confirmedAt}, {@code expiredAt}, ...) it
 * stands in for that property. Jackson 2 appears here only because the test classpath has it.
 */
class DomainEventsSchemaMappingTest {

  private static final Path SCHEMAS =
      Path.of("../../contracts/events").toAbsolutePath().normalize();

  /** event record -> schema file and the schema property that the record's occurredAt replaces. */
  private static final Map<Class<? extends DomainEvent>, String[]> MAPPING =
      Map.of(
          BookingCreated.class, new String[] {"booking-created.v1.schema.json", ""},
          BookingConfirmed.class, new String[] {"booking-confirmed.v1.schema.json", "confirmedAt"},
          BookingExpired.class, new String[] {"booking-expired.v1.schema.json", "expiredAt"},
          BookingCancelled.class, new String[] {"booking-cancelled.v1.schema.json", "cancelledAt"},
          BookingPaymentRefunded.class,
              new String[] {"booking-payment-refunded.v1.schema.json", "refundedAt"});

  private static Set<String> dataProperties(String file) throws IOException {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var schema = mapper.readTree(Files.readString(SCHEMAS.resolve(file)));
    var names = new HashSet<String>();
    schema.at("/allOf/1/properties/data/properties").fieldNames().forEachRemaining(names::add);
    return names;
  }

  @Test
  void should_cover_every_permitted_event() {
    assertThat(DomainEvent.class.getPermittedSubclasses())
        .containsExactlyInAnyOrderElementsOf(MAPPING.keySet());
  }

  @Test
  void should_carry_exactly_the_properties_of_its_schema() throws IOException {
    for (var entry : MAPPING.entrySet()) {
      String schemaTimestamp = entry.getValue()[1];
      Set<String> components =
          Arrays.stream(entry.getKey().getRecordComponents())
              .map(RecordComponent::getName)
              .map(name -> name.equals("occurredAt") ? schemaTimestamp : name)
              .filter(name -> !name.isEmpty())
              .collect(Collectors.toSet());

      assertThat(components)
          .as(entry.getKey().getSimpleName())
          .isEqualTo(dataProperties(entry.getValue()[0]));
    }
  }
}
