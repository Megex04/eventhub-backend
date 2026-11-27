package com.eventhub.booking.infrastructure.adapter.in.web;

import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The caller's identity, always taken from the {@code sub} claim of the validated JWT and never
 * from a request body or parameter.
 */
final class CurrentUser {

  private CurrentUser() {}

  /** A token whose subject is not a UUID cannot own anything: it is answered as 401. */
  static UUID requireId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
      return parseSubject(token.getToken().getSubject());
    }
    throw new BadCredentialsException("Authentication is required");
  }

  private static UUID parseSubject(String subject) {
    if (subject == null) {
      throw new BadCredentialsException("The token has no subject");
    }
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
      throw new BadCredentialsException("The token subject is not a valid user id", e);
    }
  }
}
