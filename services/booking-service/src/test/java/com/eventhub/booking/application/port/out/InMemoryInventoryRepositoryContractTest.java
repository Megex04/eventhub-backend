package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.AppFixtures;

class InMemoryInventoryRepositoryContractTest extends InventoryRepositoryContract {

  private final InventoryRepository repository =
      new InMemoryInventoryRepository(new InMemoryStore(AppFixtures.at(AppFixtures.NOW)));

  @Override
  protected InventoryRepository repository() {
    return repository;
  }
}
