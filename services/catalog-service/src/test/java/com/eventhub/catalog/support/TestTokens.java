package com.eventhub.catalog.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signed JWTs shaped like the ones Keycloak issues (roles under {@code realm_access.roles}), so the
 * tests go through the real decoder and the real role converter instead of a mocked principal.
 */
public final class TestTokens {

  public static final String ORGANIZER_ROLE = "ORGANIZER";

  private static final JwtEncoder ENCODER = encoderFor(TestSupportConfig.TOKEN_KEY);

  private TestTokens() {}

  public static String organizer(String subject) {
    return signed(ENCODER, subject, List.of(ORGANIZER_ROLE), Duration.ofMinutes(15));
  }

  /** Authenticated but without the ORGANIZER role. */
  public static String attendee(String subject) {
    return signed(ENCODER, subject, List.of(), Duration.ofMinutes(15));
  }

  public static String expired(String subject) {
    return signed(ENCODER, subject, List.of(ORGANIZER_ROLE), Duration.ofMinutes(-5));
  }

  /** Same claims as a valid organizer token, but signed with a key the service does not trust. */
  public static String signedWithUntrustedKey(String subject) {
    SecretKey other =
        new SecretKeySpec("a-different-key-the-service-ignores!".getBytes(), "HmacSHA256");
    return signed(encoderFor(other), subject, List.of(ORGANIZER_ROLE), Duration.ofMinutes(15));
  }

  private static JwtEncoder encoderFor(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  private static String signed(
      JwtEncoder encoder, String subject, List<String> roles, Duration lifetime) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("http://keycloak.test/realms/eventhub")
            .subject(subject)
            .issuedAt(now.minus(Duration.ofMinutes(10)))
            .expiresAt(now.plus(lifetime))
            .claim("realm_access", Map.of("roles", roles))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
