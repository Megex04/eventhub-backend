package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.AppFixtures;
import com.eventhub.booking.support.MutableClock;
import java.time.Instant;

class InMemoryReconciliationRepositoryContractTest extends ReconciliationRepositoryContract {

  private final MutableClock clock = new MutableClock(AppFixtures.NOW);
  private final InMemoryStore store = new InMemoryStore(clock);
  private final BookingRepository bookings = new InMemoryBookingRepository(store);
  private final ReconciliationRepository reconciliation =
      new InMemoryReconciliationRepository(store);

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
