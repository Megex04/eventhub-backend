package com.eventhub.catalog.infrastructure.adapter.in.web;

import com.eventhub.catalog.application.exception.AuthenticationRequiredException;
import com.eventhub.catalog.application.query.Viewer;
import java.util.Optional;
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

  /** Anonymous on public routes called without a token. */
  static Viewer viewer() {
    return currentId().map(Viewer::authenticated).orElseGet(Viewer::anonymous);
  }

  static UUID requireId() {
    return currentId()
        .orElseThrow(() -> new AuthenticationRequiredException("Authentication is required"));
  }

  private static Optional<UUID> currentId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
      return Optional.of(parseSubject(token.getToken().getSubject()));
    }
    return Optional.empty();
  }

  // Ids are UUIDs everywhere in the platform; a token whose subject is not one cannot own anything.
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
