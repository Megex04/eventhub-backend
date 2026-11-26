package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.EventProjectionRepositoryContract;
import com.eventhub.booking.support.PersistenceIT;
import org.springframework.beans.factory.annotation.Autowired;

/** The EventProjectionRepository contract, run against the real adapter and PostgreSQL. */
@PersistenceIT
class JpaEventProjectionRepositoryContractIT extends EventProjectionRepositoryContract {

  @Autowired private EventProjectionRepository projection;

  @Override
  protected EventProjectionRepository repository() {
    return projection;
  }
}
