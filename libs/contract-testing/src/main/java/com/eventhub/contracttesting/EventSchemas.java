package com.eventhub.contracttesting;

import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.InputStreamSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Validates JSON text against the schemas in {@code contracts/events}, loaded in place (never
 * copied into the module). The schemas reference each other by their {@code $id}; a resource loader
 * answers those ids straight from that directory. Format keywords such as {@code uuid} and {@code
 * date-time} are asserted: by default JSON Schema 2020-12 only annotates them.
 *
 * <p>The text goes straight to the validator, so no Jackson type appears in the tests.
 */
public final class EventSchemas {

  private static final String ID_BASE = "https://eventhub.dev/schemas/events/";
  private static final Path DIRECTORY =
      Path.of("../../contracts/events").toAbsolutePath().normalize();

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder
                  .schemaLoader(loader ->
                      loader.resourceLoaders(
                          resources -> resources.add(EventSchemas::fromContracts)))
                  .schemaRegistryConfig(
                      SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

  private EventSchemas() {}

  /** Serves the schema ids of the contract from the contracts directory; null for any other id. */
  private static InputStreamSource fromContracts(AbsoluteIri iri) {
    String id = iri.toString();
    if (!id.startsWith(ID_BASE)) {
      return null;
    }
    Path file = DIRECTORY.resolve(id.substring(ID_BASE.length()));
    return () -> Files.newInputStream(file);
  }

  /**
   * {@code BookingPaymentRefunded} is described by {@code booking-payment-refunded.v1.schema.json}.
   */
  private static String fileOf(String eventType) {
    return eventType.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)
        + ".v1.schema.json";
  }

  /** Empty when {@code json} is a valid message of that event type. */
  public static List<String> violations(String eventType, String json) {
    String file = fileOf(eventType);
    if (!Files.isRegularFile(DIRECTORY.resolve(file))) {
      throw new IllegalArgumentException("No schema for event type " + eventType);
    }
    Schema schema = REGISTRY.getSchema(SchemaLocation.of(ID_BASE + file));
    return schema.validate(json, InputFormat.JSON).stream().map(Error::toString).toList();
  }
}
