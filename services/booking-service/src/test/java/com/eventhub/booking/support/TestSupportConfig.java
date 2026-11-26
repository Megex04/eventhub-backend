package com.eventhub.booking.support;

import static com.eventhub.booking.application.AppFixtures.NOW;

import com.eventhub.booking.application.port.out.FakePaymentGateway;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test doubles for what an integration test cannot take from the environment: a token issuer
 * (Keycloak), the passage of time and a payment gateway the test can pause and make fail.
 */
@TestConfiguration
public class TestSupportConfig {

  /**
   * Shared secret of the test issuer; {@link TestTokens} signs with it, the decoder verifies it.
   */
  public static final SecretKey TOKEN_KEY =
      new SecretKeySpec("integration-tests-only-signing-key-32b!".getBytes(), "HmacSHA256");

  /** Validates signature and expiry like production, minus the call to the issuer's JWKS. */
  @Bean
  JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(TOKEN_KEY).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(NOW);
  }

  /** Wins over the simulated gateway of the test profile: the tests drive this one. */
  @Bean
  @Primary
  FakePaymentGateway controllablePaymentGateway() {
    return new FakePaymentGateway();
  }
}
