package com.eventhub.catalog.infrastructure.adapter.out.persistence;

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
import com.eventhub.catalog.application.service.ZoneService;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.support.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions race on the same event. A barrier makes both load it (same version) before
 * either writes, so the loser's write is guaranteed to be stale. The real use cases, adapter and
 * outbox writer run; only the barrier is added around {@code findById}.
 */
abstract class ConcurrencyIT extends PostgresIntegrationTest {

  protected static final UUID ORGANIZER = new UUID(1, 1);

  @Autowired private EventRepository repository;
  @Autowired private DomainEventPublisher publisher;
  @Autowired private IdGenerator ids;
  @Autowired private PlatformTransactionManager transactionManager;

  protected TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  protected UUID createDraftWithOneZone() {
    return createDraftWithOneZone(clock);
  }

  /** Creates the event at the instant the given clock reads, e.g. a frozen one. */
  protected UUID createDraftWithOneZone(Clock creationClock) {
    EventCommandService service =
        new EventCommandService(repository, publisher, ids, creationClock);
    Instant startsAt = creationClock.instant().plus(Duration.ofDays(30));
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
  protected EventCommandService racingCommands(CyclicBarrier barrier) {
    return racingCommands(barrier, clock);
  }

  protected EventCommandService racingCommands(CyclicBarrier barrier, Clock withClock) {
    return new EventCommandService(
        new BarrierRepository(repository, barrier), publisher, ids, withClock);
  }

  protected ZoneService racingZones(CyclicBarrier barrier, Clock withClock) {
    return new ZoneService(new BarrierRepository(repository, barrier), ids, withClock);
  }

  protected <T> Outcome race(Callable<T> first, Callable<T> second) throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<T> a = executor.submit(first);
      Future<T> b = executor.submit(second);
      return new Outcome(await(a), await(b));
    } finally {
      executor.shutdownNow();
    }
  }

  private static <T> Attempt await(Future<T> future) throws InterruptedException {
    try {
      future.get(30, TimeUnit.SECONDS);
      return new Attempt(Result.SUCCESS, null);
    } catch (ExecutionException e) {
      Result result =
          e.getCause() instanceof EventVersionConflictException
              ? Result.CONFLICT
              : Result.OTHER_FAILURE;
      return new Attempt(result, e.getCause());
    } catch (TimeoutException e) {
      return new Attempt(Result.OTHER_FAILURE, e);
    }
  }

  protected enum Result {
    SUCCESS,
    CONFLICT,
    OTHER_FAILURE
  }

  /** What one racer ended with; the cause is kept so a failed assertion can say why. */
  protected record Attempt(Result result, Throwable cause) {}

  protected record Outcome(Attempt first, Attempt second) {
    long count(Result result) {
      return java.util.stream.Stream.of(first, second).filter(a -> a.result() == result).count();
    }

    @Override
    public String toString() {
      return "first=%s (%s), second=%s (%s)"
          .formatted(first.result(), first.cause(), second.result(), second.cause());
    }
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
      } catch (BrokenBarrierException | TimeoutException e) {
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

    @Override
    public java.util.List<Event> findReplayable(Optional<UUID> after, int limit) {
      return delegate.findReplayable(after, limit);
    }
  }
}
