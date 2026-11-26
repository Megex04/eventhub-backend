package com.eventhub.booking.infrastructure.adapter.out.messaging;

import java.util.Locale;

/**
 * Routing key convention of CLAUDE.md: {@code <service>.<aggregate>.<event>.v<version>}, e.g.
 * {@code booking.booking.created.v1} for the {@code BookingCreated} of the {@code Booking}
 * aggregate. A multi-word event is written in kebab-case, as the contract names it: {@code
 * booking.booking.payment-refunded.v1}.
 */
final class RoutingKeys {

  private RoutingKeys() {}

  static String of(String service, String aggregateType, String eventType, int version) {
    String aggregate = aggregateType.toLowerCase(Locale.ROOT);
    String event =
        eventType.startsWith(aggregateType)
            ? eventType.substring(aggregateType.length())
            : eventType;
    String kebab = event.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    return "%s.%s.%s.v%d".formatted(service, aggregate, kebab, version);
  }
}
