package com.eventhub.booking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** A bad value must stop the application from starting, never fail later inside a request. */
class BookingPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(BookingProperties.class)
  static class Config {}

  private static final List<String> VALID =
      List.of(
          "eventhub.booking.hold-duration=10m",
          "eventhub.booking.max-tickets=6",
          "eventhub.booking.expiration.batch-size=50",
          "eventhub.booking.expiration.poll-interval=30s",
          "eventhub.booking.reconciler.initiated-stale-after=5m",
          "eventhub.booking.reconciler.initial-backoff=30s",
          "eventhub.booking.reconciler.max-backoff=10m",
          "eventhub.booking.reconciler.batch-size=20",
          "eventhub.booking.reconciler.poll-interval=1m",
          "eventhub.booking.cascade.batch-size=50");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  /** The valid set with one property replaced (or removed when {@code value} is null). */
  private static String[] with(String property, String value) {
    List<String> result = new ArrayList<>();
    for (String entry : VALID) {
      if (!entry.startsWith(property + "=")) {
        result.add(entry);
      }
    }
    if (value != null) {
      result.add(property + "=" + value);
    }
    return result.toArray(String[]::new);
  }

  private static String camelCase(String property) {
    StringBuilder result = new StringBuilder();
    boolean upper = false;
    for (char c : property.toCharArray()) {
      if (c == '-') {
        upper = true;
      } else {
        result.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return result.toString();
  }

  private static String stackOf(Throwable failure) {
    java.io.StringWriter text = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(text));
    return text.toString();
  }

  static Stream<Arguments> invalidValues() {
    return Stream.of(
        Arguments.of("eventhub.booking.hold-duration", "0s"),
        Arguments.of("eventhub.booking.hold-duration", "-5m"),
        Arguments.of("eventhub.booking.max-tickets", "0"),
        Arguments.of("eventhub.booking.max-tickets", "-1"),
        Arguments.of("eventhub.booking.expiration.batch-size", "0"),
        Arguments.of("eventhub.booking.expiration.poll-interval", "0s"),
        Arguments.of("eventhub.booking.reconciler.initiated-stale-after", "-1s"),
        Arguments.of("eventhub.booking.reconciler.initial-backoff", "0s"),
        Arguments.of("eventhub.booking.reconciler.max-backoff", "0s"),
        Arguments.of("eventhub.booking.reconciler.batch-size", "0"),
        Arguments.of("eventhub.booking.reconciler.poll-interval", "0s"),
        Arguments.of("eventhub.booking.cascade.batch-size", "0"));
  }

  @Test
  void should_bind_the_configured_values_when_they_are_valid() {
    runner
        .withPropertyValues(VALID.toArray(String[]::new))
        .run(context -> {
          BookingProperties properties = context.getBean(BookingProperties.class);
          assertThat(properties.holdDuration()).isEqualTo(Duration.ofMinutes(10));
          assertThat(properties.maxTickets()).isEqualTo(6);
          assertThat(properties.expiration().batchSize()).isEqualTo(50);
          assertThat(properties.reconciler().maxBackoff()).isEqualTo(Duration.ofMinutes(10));
          assertThat(properties.cascade().batchSize()).isEqualTo(50);
        });
  }

  @ParameterizedTest(name = "{0}={1} stops the startup")
  @MethodSource("invalidValues")
  void should_fail_the_startup_when_a_value_is_invalid(String property, String value) {
    runner
        .withPropertyValues(with(property, value))
        .run(context -> {
          assertThat(context).hasFailed();
          // The binder names a nested property in camelCase (batchSize), a top-level one as
          // written.
          assertThat(stackOf(context.getStartupFailure()))
              .containsAnyOf(property, camelCase(property));
        });
  }

  @Test
  void should_fail_the_startup_when_the_backoff_cap_is_below_its_start() {
    runner
        .withPropertyValues(with("eventhub.booking.reconciler.max-backoff", "10s"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("max-backoff must not be below initial-backoff");
        });
  }

  @Test
  void should_fail_the_startup_when_a_property_is_missing() {
    runner
        .withPropertyValues(with("eventhub.booking.hold-duration", null))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void should_fail_the_startup_when_a_whole_section_is_missing() {
    runner
        .withPropertyValues("eventhub.booking.hold-duration=10m", "eventhub.booking.max-tickets=6")
        .run(context -> assertThat(context).hasFailed());
  }
}
