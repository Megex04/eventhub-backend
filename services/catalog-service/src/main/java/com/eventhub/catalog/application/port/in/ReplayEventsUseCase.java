package com.eventhub.catalog.application.port.in;

import com.eventhub.catalog.domain.InvalidValueException;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrative re-emission of events (ADR 0003): writes the current state of published and
 * cancelled events to the outbox again so a consumer that missed them can catch up. Not part of the
 * public API.
 */
public interface ReplayEventsUseCase {

  int DEFAULT_LIMIT = 100;
  int MAX_LIMIT = 500;

  Result replay(Command command);

  /**
   * @param eventId a single event; when empty, a page of all replayable events
   * @param limit most events replayed by this call
   * @param after cursor: only events with an id greater than this (ignored when {@code eventId} is
   *     set)
   */
  record Command(Optional<UUID> eventId, int limit, Optional<UUID> after) {

    public Command {
      if (limit < 1 || limit > MAX_LIMIT) {
        throw new InvalidValueException(
            "limit", "limit must be between 1 and %d".formatted(MAX_LIMIT));
      }
    }

    public static Command all(int limit) {
      return new Command(Optional.empty(), limit, Optional.empty());
    }

    public static Command one(UUID eventId) {
      return new Command(Optional.of(eventId), DEFAULT_LIMIT, Optional.empty());
    }
  }

  /**
   * @param events events replayed (drafts and conflicts excluded)
   * @param messages outbox rows written
   * @param conflicts events skipped because someone changed them while being replayed; run again
   * @param next cursor for the following page, empty when there is nothing more
   */
  record Result(int events, int messages, int conflicts, Optional<UUID> next) {}
}
