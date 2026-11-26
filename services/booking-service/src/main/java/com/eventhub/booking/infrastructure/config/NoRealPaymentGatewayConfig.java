package com.eventhub.booking.infrastructure.config;

import com.eventhub.booking.application.port.out.PaymentGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * There is no real payment gateway yet, and the simulated one must never run in production. Outside
 * the {@code local}, {@code dev} and {@code test} profiles the application therefore fails at
 * startup with a clear message, rather than silently approving payments. A real adapter replaces
 * this class.
 */
@Configuration
@Profile("!local & !dev & !test")
class NoRealPaymentGatewayConfig {

  @Bean
  PaymentGateway unavailablePaymentGateway() {
    throw new IllegalStateException(
        "No real PaymentGateway is configured for this profile. The simulated gateway only runs"
            + " in the local, dev and test profiles.");
  }
}
