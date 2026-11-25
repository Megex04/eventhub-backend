package com.eventhub.booking.application.port.out;

import com.eventhub.booking.application.AppFixtures;

class InMemoryBookingRepositoryContractTest extends BookingRepositoryContract {

  private final BookingRepository repository =
      new InMemoryBookingRepository(new InMemoryStore(AppFixtures.at(AppFixtures.NOW)));

  @Override
  protected BookingRepository repository() {
    return repository;
  }
}
