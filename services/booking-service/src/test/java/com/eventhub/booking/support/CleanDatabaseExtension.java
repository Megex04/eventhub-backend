package com.eventhub.booking.support;

import static com.eventhub.booking.application.AppFixtures.NOW;

import com.eventhub.booking.application.port.out.FakePaymentGateway;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Empties every table, rewinds the clock and resets the controllable gateway before each test, so
 * tests never see each other.
 */
public final class CleanDatabaseExtension implements BeforeEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    ApplicationContext spring = SpringExtension.getApplicationContext(context);
    spring.getBean(MutableClock.class).set(NOW);
    spring.getBeanProvider(FakePaymentGateway.class).ifAvailable(FakePaymentGateway::reset);
    spring
        .getBeanProvider(ControllableOutboxBroker.class)
        .ifAvailable(ControllableOutboxBroker::reset);
    spring
        .getBean(JdbcTemplate.class)
        .execute(
            "TRUNCATE TABLE payment, booking_item, booking, zone_inventory, projected_zone,"
                + " event_projection, processed_message, outbox_event CASCADE");
  }
}
