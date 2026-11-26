package com.eventhub.booking.infrastructure.config;

import com.eventhub.booking.application.port.out.CorrelationIdProvider;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Reads the correlation id of the current request from the logging context (MDC). */
@Component
public class MdcCorrelationIdProvider implements CorrelationIdProvider {

  /** MDC key under which the web layer stores the id of the request being served. */
  public static final String MDC_KEY = "correlationId";

  /** Empty when no request context exists or the stored value is not a valid UUID. */
  @Override
  public Optional<UUID> current() {
    String value = MDC.get(MDC_KEY);
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
