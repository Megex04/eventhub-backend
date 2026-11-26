package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.BookingRepositoryContract;
import com.eventhub.booking.support.PersistenceIT;
import org.springframework.beans.factory.annotation.Autowired;

/** The BookingRepository contract, run against the real adapter and PostgreSQL. */
@PersistenceIT
class JpaBookingRepositoryContractIT extends BookingRepositoryContract {

  @Autowired private BookingRepository booking;

  @Override
  protected BookingRepository repository() {
    return booking;
  }
}
