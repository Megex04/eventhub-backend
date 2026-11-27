package com.eventhub.booking.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** The correlation id of the current request or message, when there is one. */
public interface CorrelationIdProvider {

  Optional<UUID> current();
}
