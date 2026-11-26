package com.eventhub.booking.infrastructure.adapter.in.scheduling;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** The reconciler job on the real scheduler: it finishes what the request path left behind. */
@PersistenceIT
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = {
      "eventhub.booking.reconciler.enabled=true",
      "eventhub.booking.reconciler.poll-interval=100ms",
      "eventhub.booking.reconciler.initiated-stale-after=2m",
      "eventhub.booking.reconciler.initial-backoff=1s",
      "eventhub.booking.reconciler.max-backoff=10s"
    })
class ReconcilerJobIT {

  @Autowired private ApplicationContext context;
  @Autowired private FakePaymentGateway gateway;
  private RealApp app;

  @BeforeEach
  void wire() {
    gateway.reset();
    app = new RealApp(context);
    app.clock.set(NOW);
    app.openEvent(100, 100);
  }

  private UUID pendingBooking(long n) {
    return app.create()
        .create(
            new CreateBookingCommand(
                uuid(10_000 + n), uuid(20_000 + n), EVENT, List.of(new RequestedItem(VIP_ZONE, 1))))
        .booking()
        .id();
  }

  private String paymentStatus(UUID bookingId) {
    return app.jdbc.queryForObject(
        "SELECT status FROM payment WHERE booking_id = ?", String.class, bookingId);
  }

  @Test
  void should_finish_a_pending_refund_by_itself_once_the_gateway_recovers() {
    UUID booking = pendingBooking(1);
    app.pay(gateway).pay(new PayBookingCommand(uuid(10_001), booking, "tok_approved"));
    gateway.failAllRefunds(true);
    app.cancel(gateway).cancel(uuid(10_001), booking);
    assertThat(paymentStatus(booking)).isEqualTo("REFUND_PENDING");

    gateway.failAllRefunds(false);
    app.clock.advance(Duration.ofMinutes(1));

    await().atMost(Duration.ofSeconds(20)).until(() -> paymentStatus(booking).equals("REFUNDED"));
    assertThat(gateway.distinctRefunds()).isEqualTo(1);
    app.assertInventoryInvariant();
  }

  @Test
  void should_resolve_an_orphan_charge_by_itself_once_it_is_stale() {
    UUID booking = pendingBooking(2);
    gateway.failNextChargesAfterProcessing(1);
    try {
      app.pay(gateway).pay(new PayBookingCommand(uuid(10_002), booking, "tok_approved"));
    } catch (RuntimeException unknownOutcome) {
      // the process "fell" after the gateway approved: INITIATED stays behind
    }
    assertThat(paymentStatus(booking)).isEqualTo("INITIATED");

    app.clock.advance(Duration.ofMinutes(3));

    await().atMost(Duration.ofSeconds(20)).until(() -> !paymentStatus(booking).equals("INITIATED"));
    assertThat(paymentStatus(booking)).isEqualTo("CAPTURED");
    assertThat(gateway.distinctCharges()).isEqualTo(1);
    app.assertInventoryInvariant();
  }
}
