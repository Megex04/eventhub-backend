package com.eventhub.booking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.support.SharedPostgres;
import com.eventhub.booking.support.SharedRabbit;
import com.eventhub.booking.support.TestConsumerSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The application refuses to start with a bad configuration, instead of failing later. */
class ContextStartupIT {

  /**
   * The values go in as command-line arguments, which beat application.yml; properties given to the
   * builder are only defaults and would be silently overridden by the file.
   */
  private static void start(String... properties) {
    List<String> args = new ArrayList<>(List.of("--server.port=0", "--management.server.port=0"));
    for (String property : properties) {
      args.add("--" + property);
    }
    new SpringApplicationBuilder(BookingServiceApplication.class)
        .web(WebApplicationType.SERVLET)
        .initializers(new SharedPostgres(), new SharedRabbit(), new TestConsumerSettings())
        .run(args.toArray(String[]::new))
        .close();
  }

  @Test
  void should_not_start_when_the_hold_duration_is_not_positive() {
    assertThatThrownBy(
            () -> start("spring.profiles.active=test", "eventhub.booking.hold-duration=0s"))
        .hasStackTraceContaining("hold-duration");
  }

  @Test
  void should_not_start_when_a_booking_may_hold_no_tickets() {
    assertThatThrownBy(() -> start("spring.profiles.active=test", "eventhub.booking.max-tickets=0"))
        .hasStackTraceContaining("max-tickets");
  }

  @Test
  void should_not_start_when_the_backoff_cap_is_below_its_start() {
    assertThatThrownBy(() ->
            start(
                "spring.profiles.active=test",
                "eventhub.booking.reconciler.initial-backoff=1m",
                "eventhub.booking.reconciler.max-backoff=10s"))
        .hasStackTraceContaining("max-backoff must not be below initial-backoff");
  }

  @Test
  void should_not_start_without_a_real_payment_gateway_outside_local_dev_and_test() {
    assertThatThrownBy(() -> start("spring.profiles.active=prod"))
        .hasStackTraceContaining("No real PaymentGateway is configured");
  }

  @Test
  void should_not_start_in_a_profile_nobody_named_either() {
    assertThatThrownBy(() -> start("spring.profiles.active=staging"))
        .hasStackTraceContaining("No real PaymentGateway is configured");
  }
}
