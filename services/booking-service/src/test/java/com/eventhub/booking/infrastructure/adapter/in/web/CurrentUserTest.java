package com.eventhub.booking.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticateAs(String subject) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claims(claims -> claims.putAll(Map.of("scope", "x")));
    if (subject != null) {
      jwt.subject(subject);
    }
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt.build(), AuthorityUtils.NO_AUTHORITIES));
  }

  @Test
  void should_take_the_user_id_from_the_subject_claim() {
    UUID id = UUID.randomUUID();
    authenticateAs(id.toString());

    assertThat(CurrentUser.requireId()).isEqualTo(id);
  }

  @Test
  void should_fail_authentication_when_the_subject_is_not_a_uuid() {
    authenticateAs("alice");

    assertThatThrownBy(CurrentUser::requireId).isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void should_fail_authentication_when_the_token_has_no_subject() {
    authenticateAs(null);

    assertThatThrownBy(CurrentUser::requireId).isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void should_fail_authentication_when_there_is_no_authentication_at_all() {
    assertThatThrownBy(CurrentUser::requireId).isInstanceOf(BadCredentialsException.class);
  }
}
