package com.eventhub.booking.application.port.out;

import java.util.UUID;

/** Message ids already handled, so a redelivered message is ignored. */
public interface ProcessedMessageRepository {

  boolean isProcessed(UUID messageId);

  /** True if the id was new; false if it had been recorded already. */
  boolean markProcessed(UUID messageId);
}
