package com.eventhub.catalog.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesConverterTest {

  private final KeycloakRealmRolesConverter converter = new KeycloakRealmRolesConverter();

  @Test
  void should_prefix_every_realm_role_when_the_token_carries_realm_access() {
    Jwt jwt = jwtWith(Map.of("realm_access", Map.of("roles", List.of("ORGANIZER", "AUDITOR"))));

    assertThat(converter.convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_ORGANIZER", "ROLE_AUDITOR");
  }

  @Test
  void should_grant_nothing_when_the_token_has_no_realm_access_claim() {
    assertThat(converter.convert(jwtWith(Map.of()))).isEmpty();
  }

  @Test
  void should_grant_nothing_when_realm_access_has_no_roles() {
    Jwt jwt = jwtWith(Map.of("realm_access", Map.of("other", "value")));

    assertThat(converter.convert(jwt)).isEmpty();
  }

  @Test
  void should_ignore_the_claim_when_it_has_an_unexpected_shape() {
    Jwt jwt = jwtWith(Map.of("realm_access", "ORGANIZER"));

    assertThat(converter.convert(jwt)).isEmpty();
  }

  @Test
  void should_skip_entries_that_are_not_text_when_roles_is_malformed() {
    Jwt jwt = jwtWith(Map.of("realm_access", Map.of("roles", List.of("ORGANIZER", 42, ""))));

    assertThat(converter.convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ORGANIZER");
  }

  private static Jwt jwtWith(Map<String, Object> claims) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("3f1c2a54-8b7e-4d1a-9c52-6a0f4b7d1e01")
            .issuedAt(Instant.parse("2026-06-01T12:00:00Z"))
            .expiresAt(Instant.parse("2026-06-01T12:15:00Z"));
    claims.forEach(builder::claim);
    return builder.build();
  }
}
