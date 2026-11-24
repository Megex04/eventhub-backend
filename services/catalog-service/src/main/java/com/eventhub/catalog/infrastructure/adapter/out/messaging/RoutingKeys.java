package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import java.util.Locale;

/**
 * Routing key convention of CLAUDE.md: {@code <service>.<aggregate>.<event>.v<version>}, e.g.
 * {@code catalog.event.published.v1} for the {@code EventPublished} of the {@code Event} aggregate.
 */
final class RoutingKeys {

  private RoutingKeys() {}

  static String of(String service, String aggregateType, String eventType, int version) {
    String aggregate = aggregateType.toLowerCase(Locale.ROOT);
    String event =
        eventType.startsWith(aggregateType)
            ? eventType.substring(aggregateType.length())
            : eventType;
    return "%s.%s.%s.v%d".formatted(service, aggregate, event.toLowerCase(Locale.ROOT), version);
  }
}
