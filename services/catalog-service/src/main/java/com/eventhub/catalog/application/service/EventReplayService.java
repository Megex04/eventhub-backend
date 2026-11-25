package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.application.port.in.ReplayEventsUseCase;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.domain.Event;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Picks what to replay and replays it event by event. Deliberately not transactional itself: each
 * event runs in its own transaction inside {@link EventReplayer}.
 */
@Service
public class EventReplayService implements ReplayEventsUseCase {

  private final EventRepository repository;
  private final EventReplayer replayer;

  public EventReplayService(EventRepository repository, EventReplayer replayer) {
    this.repository = repository;
    this.replayer = replayer;
  }

  @Override
  public Result replay(Command command) {
    if (command.eventId().isPresent()) {
      return replay(List.of(command.eventId().get()), Optional.empty());
    }
    List<UUID> ids =
        repository.findReplayable(command.after(), command.limit()).stream()
            .map(Event::id)
            .toList();
    Optional<UUID> next =
        ids.size() == command.limit() ? Optional.of(ids.getLast()) : Optional.empty();
    return replay(ids, next);
  }

  private Result replay(List<UUID> ids, Optional<UUID> next) {
    int events = 0;
    int messages = 0;
    int conflicts = 0;
    for (UUID id : ids) {
      try {
        int written = replayer.replay(id);
        if (written > 0) {
          events++;
          messages += written;
        }
      } catch (EventVersionConflictException e) {
        conflicts++;
      }
    }
    return new Result(events, messages, conflicts, next);
  }
}
