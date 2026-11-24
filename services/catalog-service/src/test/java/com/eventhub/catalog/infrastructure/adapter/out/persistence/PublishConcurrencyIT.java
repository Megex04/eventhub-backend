package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.application.port.in.CreateEventUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.port.out.IdGenerator;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.service.EventCommandService;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.support.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions race on the same event. A barrier makes both load it (same version) before
 * either writes, so the loser's write is guaranteed to be stale. The real use case, adapter and
 * outbox writer run; only the barrier is added around {@code findById}.
 */
class PublishConcurrencyIT extends PostgresIntegrationTest {

  private static final UUID ORGANIZER = new UUID(1, 1);

  @Autowired private EventRepository repository;
  @Autowired private DomainEventPublisher publisher;
  @Autowired private IdGenerator ids;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private UUID createDraftWithOneZone() {
    EventCommandService service = new EventCommandService(repository, publisher, ids, clock);
    Instant startsAt = clock.instant().plus(Duration.ofDays(30));
    Event event =
        transaction()
            .execute(status ->
                service.create(
                    new CreateEventUseCase.Command(
                        ORGANIZER,
                        "Rock Fest",
                        "",
                        "Estadio",
                        startsAt,
                        startsAt.plus(Duration.ofHours(3)),
                        List.of(new ZoneSpec("General", Money.of("50.00", "PEN"), 100)))));
    return event.id();
  }

  /** Wraps the adapter so that both racers hold the same loaded version before continuing. */
  private EventCommandService racingService(CyclicBarrier barrier) {
    return new EventCommandService(
        new BarrierRepository(repository, barrier), publisher, ids, clock);
  }

  private <T> Outcome race(Callable<T> first, Callable<T> second) throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<T> a = executor.submit(first);
      Future<T> b = executor.submit(second);
      return new Outcome(await(a), await(b));
    } finally {
      executor.shutdownNow();
    }
  }

  private static <T> Result await(Future<T> future) throws InterruptedException {
    try {
      future.get(30, TimeUnit.SECONDS);
      return Result.SUCCESS;
    } catch (ExecutionException e) {
      return e.getCause() instanceof EventVersionConflictException
          ? Result.CONFLICT
          : Result.OTHER_FAILURE;
    } catch (TimeoutException e) {
      return Result.OTHER_FAILURE;
    }
  }

  private enum Result {
    SUCCESS,
    CONFLICT,
    OTHER_FAILURE
  }

  private record Outcome(Result first, Result second) {
    long count(Result result) {
      return java.util.stream.Stream.of(first, second).filter(r -> r == result).count();
    }
  }

  @Test
  void should_let_exactly_one_publish_win_and_record_a_single_event_when_two_race_on_one_event()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    EventCommandService racer1 = racingService(barrier);
    EventCommandService racer2 = racingService(barrier);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> racer1.publish(eventId, ORGANIZER)),
            () -> transaction().execute(status -> racer2.publish(eventId, ORGANIZER)));

    assertThat(outcome.count(Result.SUCCESS)).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).isEqualTo(1);
    Map<String, Object> stored =
        jdbc.queryForMap("SELECT status, version FROM event WHERE id = ?", eventId);
    assertThat(stored.get("status")).isEqualTo("PUBLISHED");
    assertThat(stored.get("version")).isEqualTo(1L);
    assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event", String.class))
        .containsExactly("EventPublished");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void should_keep_the_outbox_consistent_when_publish_and_delete_race_on_one_draft()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    EventCommandService publishing = racingService(barrier);
    EventCommandService deleting = racingService(barrier);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> publishing.publish(eventId, ORGANIZER)),
            () ->
                transaction()
                    .execute(status -> {
                      deleting.delete(eventId, ORGANIZER);
                      return null;
                    }));

    assertThat(outcome.count(Result.SUCCESS)).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).isEqualTo(1);
    Integer remaining =
        jdbc.queryForObject("SELECT count(*) FROM event WHERE id = ?", Integer.class, eventId);
    Integer outboxRows = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
    if (remaining == 1) {
      assertThat(jdbc.queryForObject("SELECT status FROM event", String.class))
          .isEqualTo("PUBLISHED");
      assertThat(outboxRows).isEqualTo(1);
    } else {
      assertThat(outboxRows).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_zone", Integer.class)).isZero();
    }
  }

  @Test
  void should_publish_normally_when_there_is_no_competing_transaction() {
    UUID eventId = createDraftWithOneZone();
    EventCommandService service = new EventCommandService(repository, publisher, ids, clock);

    transaction().execute(status -> service.publish(eventId, ORGANIZER));

    assertThat(jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
        .isEqualTo(1);
  }

  /** Delegates to the real adapter and waits for the other racer after each load. */
  private static final class BarrierRepository implements EventRepository {

    private final EventRepository delegate;
    private final CyclicBarrier barrier;

    BarrierRepository(EventRepository delegate, CyclicBarrier barrier) {
      this.delegate = delegate;
      this.barrier = barrier;
    }

    @Override
    public Optional<Event> findById(UUID id) {
      Optional<Event> loaded = delegate.findById(id);
      awaitOther();
      return loaded;
    }

    private void awaitOther() {
      try {
        barrier.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the other racer", e);
      } catch (java.util.concurrent.BrokenBarrierException | TimeoutException e) {
        throw new IllegalStateException("The other racer never reached the barrier", e);
      }
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
  }
}
