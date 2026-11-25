package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.EVENT;
import static com.eventhub.booking.application.AppFixtures.GENERAL;
import static com.eventhub.booking.application.AppFixtures.NOW;
import static com.eventhub.booking.application.AppFixtures.VIP;
import static com.eventhub.booking.application.AppFixtures.openProjection;

import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.application.port.out.InMemoryBookingRepository;
import com.eventhub.booking.application.port.out.InMemoryEventProjectionRepository;
import com.eventhub.booking.application.port.out.InMemoryInventoryRepository;
import com.eventhub.booking.application.port.out.InMemoryProcessedMessageRepository;
import com.eventhub.booking.application.port.out.InMemoryReconciliationRepository;
import com.eventhub.booking.application.port.out.InMemoryStore;
import com.eventhub.booking.application.port.out.InMemoryTransactionRunner;
import com.eventhub.booking.application.port.out.RecordingDomainEventPublisher;
import com.eventhub.booking.application.port.out.SequentialIdGenerator;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.ZoneInventory;
import com.eventhub.booking.support.MutableClock;
import java.util.UUID;

/** The fakes every service test wires together, sharing one store, one clock and one gateway. */
public final class AppEnv {

  public final MutableClock clock = new MutableClock(NOW);
  public final InMemoryStore store = new InMemoryStore(clock);
  public final InMemoryTransactionRunner tx = new InMemoryTransactionRunner(store);
  public final InMemoryBookingRepository bookings = new InMemoryBookingRepository(store);
  public final InMemoryInventoryRepository inventory = new InMemoryInventoryRepository(store);
  public final InMemoryEventProjectionRepository projections =
      new InMemoryEventProjectionRepository(store);
  public final InMemoryProcessedMessageRepository processed =
      new InMemoryProcessedMessageRepository(store);
  public final InMemoryReconciliationRepository reconciliation =
      new InMemoryReconciliationRepository(store);
  public final RecordingDomainEventPublisher publisher = new RecordingDomainEventPublisher(store);
  public final SequentialIdGenerator ids = new SequentialIdGenerator(1000);
  public final FakePaymentGateway gateway = new FakePaymentGateway();

  /** The fixture event, open for sale, with inventory for both zones. */
  public AppEnv withOpenEvent() {
    projections.save(openProjection());
    inventory.upsertCapacity(EVENT, VIP.zoneId(), VIP.capacity());
    inventory.upsertCapacity(EVENT, GENERAL.zoneId(), GENERAL.capacity());
    return this;
  }

  public int reserved(UUID zoneId) {
    return inventory.findByEvent(EVENT).stream()
        .filter(zone -> zone.zoneId().equals(zoneId))
        .map(ZoneInventory::reserved)
        .findFirst()
        .orElseThrow();
  }

  public Booking booking(UUID id) {
    return bookings.findById(id).orElseThrow();
  }
}
