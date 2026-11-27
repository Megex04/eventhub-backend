package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.AppFixtures;

class InMemoryProcessedMessageRepositoryContractTest extends ProcessedMessageRepositoryContract {

  private final ProcessedMessageRepository repository =
      new InMemoryProcessedMessageRepository(new InMemoryStore(AppFixtures.at(AppFixtures.NOW)));

  @Override
  protected ProcessedMessageRepository repository() {
    return repository;
  }
}
