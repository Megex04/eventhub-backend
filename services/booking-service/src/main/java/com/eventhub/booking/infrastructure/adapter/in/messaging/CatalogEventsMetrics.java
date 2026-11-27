package com.eventhub.booking.infrastructure.adapter.in.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the consumer of catalog events lets operators see: messages applied, failed attempts (a
 * retried message fails several times) and messages that ended in the dead-letter queue. A
 * dead-letter count that grows is a message nobody applied and somebody has to look at.
 */
@Component
class CatalogEventsMetrics {

  static final String CONSUMED = "catalog.events.consumed";
  static final String FAILED = "catalog.events.failed";
  static final String DEAD_LETTERED = "catalog.events.dead.lettered";

  /** The type of a message that could not even be read. */
  static final String UNREADABLE = "unreadable";

  private final MeterRegistry meters;
  private final Counter deadLettered;

  CatalogEventsMetrics(MeterRegistry meters) {
    this.meters = meters;
    this.deadLettered =
        Counter.builder(DEAD_LETTERED)
            .description("Catalog messages sent to the dead-letter queue")
            .register(meters);
  }

  void consumed(String type) {
    meters.counter(CONSUMED, "type", type).increment();
  }

  void failed(String type) {
    meters.counter(FAILED, "type", type).increment();
  }

  void deadLettered() {
    deadLettered.increment();
  }
}
