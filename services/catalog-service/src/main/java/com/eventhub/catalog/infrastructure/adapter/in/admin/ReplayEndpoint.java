package com.eventhub.catalog.infrastructure.adapter.in.admin;

import com.eventhub.catalog.application.port.in.ReplayEventsUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * {@code POST /actuator/replay}: administrative re-emission of events (ADR 0003). It lives in
 * Actuator, not in the public API, so it is not in the OpenAPI contract; it requires the ADMIN role
 * (see SecurityConfig), which is not ORGANIZER. Only translates the request to the use case.
 *
 * <p>Body (all optional): {@code {"eventId": "...", "limit": 100, "after": "..."}}.
 */
@Component
@Endpoint(id = "replay")
class ReplayEndpoint {

  private final ReplayEventsUseCase replays;

  ReplayEndpoint(ReplayEventsUseCase replays) {
    this.replays = replays;
  }

  @WriteOperation
  public ReplayResponse replay(
      @Nullable UUID eventId, @Nullable Integer limit, @Nullable UUID after) {
    var command =
        new ReplayEventsUseCase.Command(
            java.util.Optional.ofNullable(eventId),
            limit == null ? ReplayEventsUseCase.DEFAULT_LIMIT : limit,
            java.util.Optional.ofNullable(after));
    return ReplayResponse.of(replays.replay(command));
  }

  /** {@code next} is absent when there is nothing more to replay. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ReplayResponse(int events, int messages, int conflicts, @Nullable UUID next) {

    static ReplayResponse of(ReplayEventsUseCase.Result result) {
      return new ReplayResponse(
          result.events(), result.messages(), result.conflicts(), result.next().orElse(null));
    }
  }
}
