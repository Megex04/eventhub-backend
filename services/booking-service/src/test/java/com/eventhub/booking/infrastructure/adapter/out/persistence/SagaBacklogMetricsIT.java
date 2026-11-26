package com.eventhub.booking.infrastructure.adapter.out.persistence;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.out.FakePaymentGateway;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

@PersistenceIT
class SagaBacklogMetricsIT {

  @Autowired private ApplicationContext context;
  @Autowired private MeterRegistry meters;
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

  private double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  @Test
  void should_report_zero_when_nothing_is_left_undone() {
    pendingBooking(1);

    assertThat(gauge("refund_pending")).isZero();
    assertThat(gauge("initiated_stale")).isZero();
    assertThat(gauge("bookings.pending.overdue")).isZero();
  }

  @Test
  void should_count_the_pending_bookings_whose_hold_ended_until_they_expire() {
    pendingBooking(1);
    pendingBooking(2);
    app.clock.set(EXPIRES_AT);

    assertThat(gauge("bookings.pending.overdue")).isEqualTo(2);

    app.expire().expireDue(10);

    assertThat(gauge("bookings.pending.overdue")).isZero();
  }

  @Test
  void should_count_a_refund_left_pending_until_it_is_reconciled() {
    UUID booking = pendingBooking(1);
    app.pay(gateway).pay(new PayBookingCommand(uuid(10_001), booking, "tok_approved"));
    gateway.failAllRefunds(true);
    app.cancel(gateway).cancel(uuid(10_001), booking);

    assertThat(gauge("refund_pending")).isEqualTo(1);
  }

  @Test
  void should_count_an_initiated_charge_only_after_the_stale_threshold() {
    UUID booking = pendingBooking(1);
    gateway.failNextChargesAfterProcessing(1);
    try {
      app.pay(gateway).pay(new PayBookingCommand(uuid(10_001), booking, "tok_approved"));
    } catch (RuntimeException unknownOutcome) {
      // INITIATED stays behind
    }

    assertThat(gauge("initiated_stale")).isZero();

    app.clock.advance(Duration.ofMinutes(3));

    assertThat(gauge("initiated_stale")).isEqualTo(1);
  }
}
