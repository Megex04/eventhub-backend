package com.eventhub.catalog.application.query;

import com.eventhub.catalog.domain.Event;
import java.util.UUID;

/** Who is looking: nobody in particular (public routes) or an authenticated user. */
public sealed interface Viewer {

  record Anonymous() implements Viewer {}

  record Authenticated(UUID userId) implements Viewer {
    public Authenticated {
      if (userId == null) {
        throw new IllegalArgumentException("userId is required");
      }
    }
  }

  static Viewer anonymous() {
    return new Anonymous();
  }

  static Viewer authenticated(UUID userId) {
    return new Authenticated(userId);
  }

  default boolean isAuthenticated() {
    return this instanceof Authenticated;
  }

  default boolean isOrganizerOf(Event event) {
    return switch (this) {
      case Authenticated user -> event.isOrganizedBy(user.userId());
      case Anonymous anonymous -> false;
    };
  }
}
