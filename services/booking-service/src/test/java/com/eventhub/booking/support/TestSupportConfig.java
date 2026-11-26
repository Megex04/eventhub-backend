package com.eventhub.booking.support;

import static com.eventhub.booking.application.AppFixtures.NOW;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with one the tests control. */
@TestConfiguration
public class TestSupportConfig {

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(NOW);
  }
}
