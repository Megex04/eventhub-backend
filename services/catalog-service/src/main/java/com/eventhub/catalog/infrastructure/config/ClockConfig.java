package com.eventhub.catalog.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

  /** Single time source of the service; everything is UTC. Tests replace it with a fixed clock. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
