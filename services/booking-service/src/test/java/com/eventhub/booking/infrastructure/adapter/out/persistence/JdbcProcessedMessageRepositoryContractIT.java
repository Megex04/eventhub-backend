package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import com.eventhub.booking.application.port.out.ProcessedMessageRepositoryContract;
import com.eventhub.booking.support.PersistenceIT;
import org.springframework.beans.factory.annotation.Autowired;

/** The ProcessedMessageRepository contract, run against the real adapter and PostgreSQL. */
@PersistenceIT
class JdbcProcessedMessageRepositoryContractIT extends ProcessedMessageRepositoryContract {

  @Autowired private ProcessedMessageRepository processed;

  @Override
  protected ProcessedMessageRepository repository() {
    return processed;
  }
}
