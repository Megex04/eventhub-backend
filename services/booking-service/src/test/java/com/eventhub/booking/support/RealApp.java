package com.eventhub.booking.support;

import static com.eventhub.booking.application.AppFixtures.EVENT;
import static com.eventhub.booking.application.AppFixtures.EVENT_STARTS;
import static com.eventhub.booking.application.AppFixtures.GENERAL;
import static com.eventhub.booking.application.AppFixtures.NOW;
import static com.eventhub.booking.application.AppFixtures.POLICY;
import static com.eventhub.booking.application.AppFixtures.VIP;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.IdGenerator;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.PaymentMetrics;
import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import com.eventhub.booking.application.port.out.ReconciliationRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.application.service.CancelBookingService;
import com.eventhub.booking.application.service.CreateBookingService;
import com.eventhub.booking.application.service.ExpireBookingsService;
import com.eventhub.booking.application.service.PayBookingService;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.ProjectedZone;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The real adapters of the running context, with the application services wired on top. */
public final class RealApp {

  public final TransactionRunner tx;
  public final BookingRepository bookings;
  public final InventoryRepository inventory;
  public final EventProjectionRepository projections;
  public final ProcessedMessageRepository processed;
  public final ReconciliationRepository reconciliation;
  public final IdGenerator ids;
  public final PaymentMetrics metrics;
  public final MutableClock clock;
  public final JdbcTemplate jdbc;
  public final DomainEventPublisher publisher;

  public RealApp(ApplicationContext context) {
    this.tx = context.getBean(TransactionRunner.class);
    this.publisher = context.getBean(DomainEventPublisher.class);
    this.bookings = context.getBean(BookingRepository.class);
    this.inventory = context.getBean(InventoryRepository.class);
    this.projections = context.getBean(EventProjectionRepository.class);
    this.processed = context.getBean(ProcessedMessageRepository.class);
    this.reconciliation = context.getBean(ReconciliationRepository.class);
    this.ids = context.getBean(IdGenerator.class);
    this.clock = context.getBean(MutableClock.class);
    this.metrics = context.getBean(PaymentMetrics.class);
    this.jdbc = context.getBean(JdbcTemplate.class);
  }

  public CreateBookingService create() {
    return new CreateBookingService(
        tx, bookings, inventory, projections, publisher, ids, clock, POLICY);
  }

  public ExpireBookingsService expire() {
    return new ExpireBookingsService(tx, bookings, inventory, publisher, clock);
  }

  public CancelBookingService cancel(PaymentGateway gateway) {
    return new CancelBookingService(
        tx, bookings, inventory, projections, publisher, gateway, clock, metrics);
  }

  public PayBookingService pay(PaymentGateway gateway) {
    return new PayBookingService(tx, bookings, publisher, ids, gateway, clock, metrics);
  }

  /** The fixture event, open, with the given capacity in its VIP and General zones. */
  public void openEvent(int vipCapacity, int generalCapacity) {
    ProjectedZone vip = new ProjectedZone(VIP.zoneId(), "VIP", VIP.price(), vipCapacity);
    ProjectedZone general =
        new ProjectedZone(GENERAL.zoneId(), "General", GENERAL.price(), generalCapacity);
    tx.run(() -> {
      projections.save(
          EventProjection.published(
              EVENT,
              EVENT_STARTS,
              EVENT_STARTS.plus(Duration.ofHours(3)),
              List.of(vip, general),
              NOW));
      inventory.upsertCapacity(EVENT, vip.zoneId(), vipCapacity);
      inventory.upsertCapacity(EVENT, general.zoneId(), generalCapacity);
    });
  }

  public long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  public int reserved(java.util.UUID zoneId) {
    Integer value =
        jdbc.queryForObject(
            "SELECT reserved FROM zone_inventory WHERE zone_id = ?", Integer.class, zoneId);
    return value == null ? 0 : value;
  }

  /**
   * The invariant of the whole design: in every zone, 0 <= reserved <= capacity and reserved equals
   * the tickets of the PENDING and CONFIRMED bookings that hold them.
   */
  public void assertInventoryInvariant() {
    List<Map<String, Object>> zones =
        jdbc.queryForList(
            "SELECT z.zone_id, z.capacity, z.reserved,"
                + " (SELECT COALESCE(SUM(i.quantity), 0) FROM booking b"
                + "   JOIN booking_item i ON i.booking_id = b.id"
                + "  WHERE b.event_id = z.event_id AND i.zone_id = z.zone_id"
                + "    AND b.status IN ('PENDING', 'CONFIRMED')) AS held"
                + " FROM zone_inventory z");
    assertThat(zones).isNotEmpty();
    for (Map<String, Object> zone : zones) {
      int capacity = ((Number) zone.get("capacity")).intValue();
      int reserved = ((Number) zone.get("reserved")).intValue();
      long held = ((Number) zone.get("held")).longValue();
      assertThat(reserved).as("reserved of zone %s", zone.get("zone_id")).isBetween(0, capacity);
      assertThat((long) reserved)
          .as("reserved of zone %s equals the tickets of live bookings", zone.get("zone_id"))
          .isEqualTo(held);
    }
  }
}
