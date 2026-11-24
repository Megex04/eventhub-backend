package com.eventhub.catalog.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Id that ties the work of one request to the events it causes. The use cases do not read it
 * themselves: how it is carried (HTTP header, logging context) is an infrastructure concern.
 */
public interface CorrelationIdProvider {

  /** Empty when the current work did not start from a request that carries one. */
  Optional<UUID> current();
}
