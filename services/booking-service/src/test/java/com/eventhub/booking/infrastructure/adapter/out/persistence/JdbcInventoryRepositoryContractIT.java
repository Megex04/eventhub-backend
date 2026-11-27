package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.InventoryRepositoryContract;
import com.eventhub.booking.support.PersistenceIT;
import org.springframework.beans.factory.annotation.Autowired;

/** The InventoryRepository contract, run against the real adapter and PostgreSQL. */
@PersistenceIT
class JdbcInventoryRepositoryContractIT extends InventoryRepositoryContract {

  @Autowired private InventoryRepository inventory;

  @Override
  protected InventoryRepository repository() {
    return inventory;
  }
}
