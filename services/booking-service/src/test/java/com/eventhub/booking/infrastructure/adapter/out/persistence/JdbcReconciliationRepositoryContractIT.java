package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ReconciliationRepository;
import com.eventhub.booking.application.port.out.ReconciliationRepositoryContract;
import com.eventhub.booking.support.MutableClock;
import com.eventhub.booking.support.PersistenceIT;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;

/** The reconciliation contract, run against the real adapters and PostgreSQL. */
@PersistenceIT
class JdbcReconciliationRepositoryContractIT extends ReconciliationRepositoryContract {

  @Autowired private BookingRepository bookings;
  @Autowired private ReconciliationRepository reconciliation;
  @Autowired private MutableClock clock;

  @Override
  protected BookingRepository bookings() {
    return bookings;
  }

  @Override
  protected ReconciliationRepository reconciliation() {
    return reconciliation;
  }

  @Override
  protected void setNow(Instant instant) {
    clock.set(instant);
  }

  @Override
  protected Instant now() {
    return clock.instant();
  }
}
