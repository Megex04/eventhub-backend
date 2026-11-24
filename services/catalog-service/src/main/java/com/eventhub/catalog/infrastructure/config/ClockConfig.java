package com.eventhub.catalog.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

  /**
   * Single time source of the service; everything is UTC. It ticks in whole microseconds, the
   * precision PostgreSQL stores, so an instant read back from the database equals the one the
   * aggregate held in memory. Tests replace it with a fixed clock.
   */
  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }
}
