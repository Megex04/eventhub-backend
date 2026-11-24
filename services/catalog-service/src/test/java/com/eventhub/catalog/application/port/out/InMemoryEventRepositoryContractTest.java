package com.eventhub.catalog.application.port.out;

/** Runs the repository contract against the in-memory fake used by the service tests. */
class InMemoryEventRepositoryContractTest extends EventRepositoryContract {

  private InMemoryEventRepository repository = new InMemoryEventRepository();

  @Override
  protected EventRepository repository() {
    return repository;
  }

  @Override
  protected void resetStorage() {
    repository = new InMemoryEventRepository();
  }
}
