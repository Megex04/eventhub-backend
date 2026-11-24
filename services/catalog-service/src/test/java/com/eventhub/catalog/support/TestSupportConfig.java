package com.eventhub.catalog.support;

import com.eventhub.catalog.infrastructure.adapter.out.persistence.JpaEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test doubles for the two things an integration test cannot take from the environment: a token
 * issuer (Keycloak) and the passage of time.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

  /**
   * Shared secret of the test issuer; {@link TestTokens} signs with it, the decoder verifies it.
   */
  static final SecretKey TOKEN_KEY =
      new SecretKeySpec("integration-tests-only-signing-key-32b!".getBytes(), "HmacSHA256");

  /** Validates signature and expiry like production, minus the call to the issuer's JWKS. */
  @Bean
  JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(TOKEN_KEY).macAlgorithm(MacAlgorithm.HS256).build();
  }

  /**
   * Primary {@code EventRepository}: the real adapter plus a hook between load and save, so tests
   * can make a concurrent transaction land at the exact moment that matters.
   */
  @Bean
  @Primary
  HookableEventRepository hookableEventRepository(JpaEventRepository real) {
    return new HookableEventRepository(real);
  }

  @Bean
  @Primary
  AdjustableClock adjustableClock() {
    return new AdjustableClock();
  }

  /**
   * The real clock plus an offset tests can move forward, so "the event has already started" can be
   * exercised without sleeping. Reset before every test by {@link PostgresIntegrationTest}.
   */
  public static final class AdjustableClock extends Clock {

    // Same microsecond precision as production, so values read back from PostgreSQL compare equal.
    private final Clock base = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration duration) {
      offset = offset.plus(duration);
    }

    public void reset() {
      offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return base.instant().plus(offset);
    }
  }
}
