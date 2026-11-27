package com.eventhub.booking.infrastructure.adapter.in.scheduling;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.service.ExpireBookingsService;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.support.Concurrently;
import com.eventhub.booking.support.PersistenceIT;
import com.eventhub.booking.support.RealApp;
import com.eventhub.booking.support.ScheduledJobs;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The expiration job on the real scheduler, and two instances of the use case side by side. The
 * context stays cached (its properties are its own), so the jobs are cancelled when the class ends;
 * the pool is bounded by the test profile (SharedPostgres).
 */
@PersistenceIT
@TestPropertySource(
    properties = {
      "eventhub.booking.expiration.enabled=true",
      "eventhub.booking.expiration.poll-interval=100ms",
      "eventhub.booking.expiration.batch-size=7"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpirationJobIT {

  @Autowired private ApplicationContext context;
  private RealApp app;

  @AfterAll
  void stopTheJobs() {
    ScheduledJobs.cancelAll(context);
  }

  @BeforeEach
  void wire() {
    app = new RealApp(context);
    app.clock.set(NOW);
    app.openEvent(100, 100);
  }

  private void createPending(int count) {
    for (int i = 0; i < count; i++) {
      app.create()
          .create(
              new CreateBookingCommand(
                  uuid(10_000 + i),
                  uuid(20_000 + i),
                  EVENT,
                  List.of(new RequestedItem(VIP_ZONE, 1))));
    }
  }

  private long expiredEvents() {
    return app.count("SELECT count(*) FROM outbox_event WHERE event_type = 'BookingExpired'");
  }

  @Test
  void should_expire_every_due_booking_in_batches_when_the_job_runs_by_itself() {
    createPending(20);
    app.clock.set(EXPIRES_AT);

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> app.count("SELECT count(*) FROM booking WHERE status = 'EXPIRED'") == 20);

    assertThat(app.reserved(VIP_ZONE)).isZero();
    assertThat(expiredEvents()).isEqualTo(20);
    app.assertInventoryInvariant();
  }

  @Test
  void should_not_expire_a_booking_whose_hold_has_not_ended_when_the_job_runs() {
    createPending(3);
    app.clock.set(EXPIRES_AT.minusSeconds(1));

    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .until(() -> app.count("SELECT count(*) FROM booking WHERE status = 'PENDING'") == 3);

    assertThat(app.reserved(VIP_ZONE)).isEqualTo(3);
  }

  @Test
  void should_expire_each_booking_exactly_once_when_two_instances_run_at_the_same_time()
      throws Exception {
    createPending(60);
    app.clock.set(EXPIRES_AT);
    ExpireBookingsService first = app.expire();
    ExpireBookingsService second = app.expire();

    Concurrently.run(2, i -> () -> (i == 0 ? first : second).expireDue(100));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> app.count("SELECT count(*) FROM booking WHERE status = 'PENDING'") == 0);
    assertThat(expiredEvents()).as("one BookingExpired per booking").isEqualTo(60);
    assertThat(app.reserved(VIP_ZONE)).as("tickets released once").isZero();
    app.assertInventoryInvariant();
  }
}
