package com.eventhub.booking.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one source of "now": UTC, truncated to microseconds, which is what PostgreSQL stores. */
@Configuration
class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }
}
