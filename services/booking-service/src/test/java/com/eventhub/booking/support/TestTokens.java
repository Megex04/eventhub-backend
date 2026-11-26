package com.eventhub.booking.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signed JWTs shaped like the ones Keycloak issues for a customer (no special role: anybody who is
 * authenticated may book), so the tests go through the real decoder instead of a mocked principal.
 */
public final class TestTokens {

  private static final JwtEncoder ENCODER = encoderFor(TestSupportConfig.TOKEN_KEY);

  private TestTokens() {}

  public static String forUser(UUID userId) {
    return signed(ENCODER, userId.toString(), Duration.ofMinutes(15));
  }

  /** Valid signature, but the subject is not a UUID: it cannot own anything. */
  public static String withSubject(String subject) {
    return signed(ENCODER, subject, Duration.ofMinutes(15));
  }

  public static String expired(UUID userId) {
    return signed(ENCODER, userId.toString(), Duration.ofMinutes(-5));
  }

  public static String signedWithUntrustedKey(UUID userId) {
    SecretKey other =
        new SecretKeySpec("a-different-key-the-service-ignores!".getBytes(), "HmacSHA256");
    return signed(encoderFor(other), userId.toString(), Duration.ofMinutes(15));
  }

  private static JwtEncoder encoderFor(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  private static String signed(JwtEncoder encoder, String subject, Duration lifetime) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("http://keycloak.test/realms/eventhub")
            .subject(subject)
            .issuedAt(now.minus(Duration.ofMinutes(10)))
            .expiresAt(now.plus(lifetime))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
