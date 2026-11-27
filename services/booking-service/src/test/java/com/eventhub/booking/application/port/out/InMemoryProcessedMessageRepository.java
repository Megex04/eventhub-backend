package com.eventhub.booking.application.port.out;

import java.util.UUID;

public final class InMemoryProcessedMessageRepository implements ProcessedMessageRepository {

  private final InMemoryStore store;

  public InMemoryProcessedMessageRepository(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public boolean isProcessed(UUID messageId) {
    return store.transaction(() -> store.processed.contains(messageId));
  }

  @Override
  public boolean markProcessed(UUID messageId) {
    return store.transaction(() -> store.processed.add(messageId));
  }
}
