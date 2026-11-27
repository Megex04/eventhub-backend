package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.AppFixtures;

class InMemoryEventProjectionRepositoryContractTest extends EventProjectionRepositoryContract {

  private final EventProjectionRepository repository =
      new InMemoryEventProjectionRepository(new InMemoryStore(AppFixtures.at(AppFixtures.NOW)));

  @Override
  protected EventProjectionRepository repository() {
    return repository;
  }
}
