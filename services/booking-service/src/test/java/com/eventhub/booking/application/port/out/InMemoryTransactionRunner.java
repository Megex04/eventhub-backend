package com.eventhub.booking.application.port.out;

import java.util.function.Supplier;

public final class InMemoryTransactionRunner implements TransactionRunner {

  private final InMemoryStore store;

  public InMemoryTransactionRunner(InMemoryStore store) {
    this.store = store;
  }

  @Override
  public <T> T execute(Supplier<T> work) {
    return store.transaction(work);
  }
}
