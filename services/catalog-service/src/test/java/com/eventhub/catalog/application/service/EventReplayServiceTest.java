package com.eventhub.catalog.application.service;

import static com.eventhub.catalog.application.service.Fixture.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.port.in.ReplayEventsUseCase;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.domain.DomainEvent;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventCancelled;
import com.eventhub.catalog.domain.EventPublished;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.InvalidValueException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EventReplayServiceTest {

  private final Fixture fixture = new Fixture();

  private ReplayEventsUseCase.Result replayAll() {
    return fixture.replays.replay(ReplayEventsUseCase.Command.all(100));
  }

  private List<String> publishedTypesAndIds() {
    return fixture.publisher.published().stream().map(EventReplayServiceTest::describe).toList();
  }

  private static String describe(DomainEvent event) {
    return switch (event) {
      case EventPublished e -> "Published:" + e.eventId();
      case EventCancelled e -> "Cancelled:" + e.eventId();
      default -> throw new AssertionError("Unexpected " + event);
    };
  }

  @Test
  void should_replay_published_and_cancelled_events_and_skip_drafts_when_everything_is_requested() {
    fixture.givenDraft(OWNER);
    Event published = fixture.givenPublished(OWNER);
    Event cancelled = fixture.givenCancelled(OWNER);

    ReplayEventsUseCase.Result result = replayAll();

    assertThat(result.events()).isEqualTo(2);
    assertThat(result.messages()).isEqualTo(3);
    assertThat(result.conflicts()).isZero();
    assertThat(publishedTypesAndIds())
        .containsExactly(
            "Published:" + published.id(),
            "Published:" + cancelled.id(),
            "Cancelled:" + cancelled.id());
  }

  @Test
  void should_publish_nothing_when_every_event_is_a_draft() {
    fixture.givenDraft(OWNER);
    fixture.givenDraft(OWNER);

    ReplayEventsUseCase.Result result = replayAll();

    assertThat(result.events()).isZero();
    assertThat(result.messages()).isZero();
    assertThat(fixture.publisher.published()).isEmpty();
  }

  @Test
  void should_replay_one_event_when_its_id_is_given() {
    fixture.givenPublished(OWNER);
    Event cancelled = fixture.givenCancelled(OWNER);

    ReplayEventsUseCase.Result result =
        fixture.replays.replay(ReplayEventsUseCase.Command.one(cancelled.id()));

    assertThat(result.events()).isEqualTo(1);
    assertThat(result.messages()).isEqualTo(2);
    assertThat(result.next()).as("a single event has no next page").isEmpty();
    assertThat(publishedTypesAndIds())
        .containsExactly("Published:" + cancelled.id(), "Cancelled:" + cancelled.id());
  }

  @Test
  void should_fail_with_not_found_when_the_event_does_not_exist() {
    assertThatThrownBy(
            () -> fixture.replays.replay(ReplayEventsUseCase.Command.one(UUID.randomUUID())))
        .isInstanceOf(EventNotFoundException.class);
  }

  @Test
  void should_replay_nothing_when_the_requested_event_is_a_draft() {
    Event draft = fixture.givenDraft(OWNER);

    ReplayEventsUseCase.Result result =
        fixture.replays.replay(ReplayEventsUseCase.Command.one(draft.id()));

    assertThat(result.events()).isZero();
    assertThat(fixture.publisher.published()).isEmpty();
  }

  @Test
  void should_cover_every_event_once_when_it_is_paged_with_the_cursor() {
    Event first = fixture.givenPublished(OWNER);
    Event second = fixture.givenPublished(OWNER);
    Event third = fixture.givenPublished(OWNER);

    ReplayEventsUseCase.Result page1 = fixture.replays.replay(ReplayEventsUseCase.Command.all(2));
    ReplayEventsUseCase.Result page2 =
        fixture.replays.replay(new ReplayEventsUseCase.Command(Optional.empty(), 2, page1.next()));

    assertThat(page1.events()).isEqualTo(2);
    assertThat(page1.next()).contains(second.id());
    assertThat(page2.events()).isEqualTo(1);
    assertThat(page2.next()).as("fewer than the limit: nothing left").isEmpty();
    assertThat(publishedTypesAndIds())
        .containsExactly(
            "Published:" + first.id(), "Published:" + second.id(), "Published:" + third.id());
  }

  @Test
  void should_change_nothing_but_the_version_when_an_event_is_replayed() {
    Event before = fixture.givenCancelled(OWNER);

    replayAll();

    Event after = fixture.reload(before);
    assertThat(after.status()).isEqualTo(EventStatus.CANCELLED);
    assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
    assertThat(after.cancelReason()).isEqualTo(before.cancelReason());
    assertThat(after.zones()).hasSameSizeAs(before.zones());
    assertThat(after.version())
        .as("the replay is a write on the aggregate")
        .isEqualTo(before.version() + 1);
  }

  @Test
  void should_send_the_same_events_each_time_when_the_replay_is_run_twice() {
    fixture.givenPublished(OWNER);
    fixture.givenCancelled(OWNER);

    ReplayEventsUseCase.Result first = replayAll();
    List<DomainEvent> firstRun = fixture.publisher.published();
    ReplayEventsUseCase.Result second = replayAll();
    List<DomainEvent> both = fixture.publisher.published();

    assertThat(second.events()).isEqualTo(first.events());
    assertThat(second.messages()).isEqualTo(first.messages());
    assertThat(both.subList(firstRun.size(), both.size())).isEqualTo(firstRun);
  }

  @Test
  void should_count_a_conflict_and_go_on_when_an_event_changes_during_the_replay() {
    Event first = fixture.givenPublished(OWNER);
    Event second = fixture.givenPublished(OWNER);
    AtomicBoolean interfered = new AtomicBoolean();
    // A concurrent writer commits between the replayer's load and its save of the first event.
    EventRepository racing =
        new AfterLoadRepository(
            fixture.repository,
            () -> {
              if (interfered.compareAndSet(false, true)) {
                fixture.repository.save(fixture.reload(first));
              }
            });
    EventReplayService service =
        new EventReplayService(fixture.repository, new EventReplayer(racing, fixture.publisher));

    ReplayEventsUseCase.Result result = service.replay(ReplayEventsUseCase.Command.all(100));

    assertThat(result.conflicts()).isEqualTo(1);
    assertThat(result.events()).isEqualTo(1);
    assertThat(publishedTypesAndIds())
        .as("the conflicting event wrote nothing")
        .containsExactly("Published:" + second.id());
  }

  @Test
  void should_reject_the_command_when_the_limit_is_out_of_range() {
    assertThatThrownBy(() -> ReplayEventsUseCase.Command.all(0))
        .isInstanceOf(InvalidValueException.class);
    assertThatThrownBy(() -> ReplayEventsUseCase.Command.all(ReplayEventsUseCase.MAX_LIMIT + 1))
        .isInstanceOf(InvalidValueException.class);
  }

  /** The real fake, plus a callback right after every load. */
  private record AfterLoadRepository(EventRepository delegate, Runnable afterLoad)
      implements EventRepository {

    @Override
    public Optional<Event> findById(UUID id) {
      Optional<Event> loaded = delegate.findById(id);
      afterLoad.run();
      return loaded;
    }

    @Override
    public void save(Event event) {
      delegate.save(event);
    }

    @Override
    public void delete(Event event) {
      delegate.delete(event);
    }

    @Override
    public PageResult<EventSummary> search(
        EventSearchCriteria criteria, EventSort sort, PageRequest page) {
      return delegate.search(criteria, sort, page);
    }

    @Override
    public List<Event> findReplayable(Optional<UUID> after, int limit) {
      return delegate.findReplayable(after, limit);
    }
  }
}
