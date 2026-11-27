package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.exception.IdempotencyKeyReuseException;
import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.CreateBookingResult;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.application.port.out.StoredBooking;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingCreated;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.BusinessRuleViolationException;
import com.eventhub.booking.domain.BusinessRuleViolationException.Rule;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CreateBookingServiceTest {

  private static final UUID KEY = uuid(500);

  private final AppEnv env = new AppEnv().withOpenEvent();
  private final CreateBookingService service = service(env.bookings);

  private CreateBookingService service(BookingRepository bookings) {
    return new CreateBookingService(
        env.tx,
        bookings,
        env.inventory,
        env.projections,
        env.publisher,
        env.ids,
        env.clock,
        POLICY);
  }

  private static CreateBookingCommand command(UUID userId, UUID key, RequestedItem... items) {
    return new CreateBookingCommand(userId, key, EVENT, List.of(items));
  }

  @Test
  void should_create_a_pending_booking_and_hold_its_tickets() {
    CreateBookingResult result = service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 2)));

    assertThat(result.created()).isTrue();
    Booking booking = result.booking();
    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
    assertThat(booking.userId()).isEqualTo(USER);
    assertThat(booking.expiresAt()).isEqualTo(NOW.plus(HOLD));
    assertThat(env.booking(booking.id()).total().amount()).hasToString("300.00");
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_publish_a_booking_created_event() {
    Booking booking = service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 2))).booking();

    assertThat(env.publisher.eventsOfType(BookingCreated.class))
        .singleElement()
        .satisfies(event -> assertThat(event.bookingId()).isEqualTo(booking.id()));
  }

  @Test
  void should_return_the_same_booking_without_holding_again_when_the_request_is_repeated() {
    CreateBookingCommand same = command(USER, KEY, new RequestedItem(VIP_ZONE, 2));
    CreateBookingResult first = service.create(same);

    CreateBookingResult second = service.create(same);

    assertThat(second.created()).isFalse();
    assertThat(second.booking().id()).isEqualTo(first.booking().id());
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
    assertThat(env.publisher.eventsOfType(BookingCreated.class)).hasSize(1);
  }

  @Test
  void should_treat_the_request_as_the_same_when_only_the_order_of_the_items_differs() {
    CreateBookingResult first =
        service.create(
            command(USER, KEY, new RequestedItem(VIP_ZONE, 1), new RequestedItem(GENERAL_ZONE, 2)));

    CreateBookingResult second =
        service.create(
            command(USER, KEY, new RequestedItem(GENERAL_ZONE, 2), new RequestedItem(VIP_ZONE, 1)));

    assertThat(second.created()).isFalse();
    assertThat(second.booking().id()).isEqualTo(first.booking().id());
  }

  @Test
  void should_return_the_current_state_when_the_repeated_request_finds_the_booking_changed() {
    CreateBookingResult first = service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 1)));
    Booking stored = env.booking(first.booking().id());
    stored.cancel(com.eventhub.booking.domain.CancelReason.USER_REQUEST, EVENT_STARTS, env.clock);
    env.bookings.save(stored);

    CreateBookingResult again = service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 1)));

    assertThat(again.created()).isFalse();
    assertThat(again.booking().status()).isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void should_reject_the_key_and_hold_nothing_when_it_is_reused_with_another_body() {
    service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 2)));

    assertThatThrownBy(() -> service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 3))))
        .isInstanceOf(IdempotencyKeyReuseException.class);
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_not_mix_the_keys_of_different_users() {
    CreateBookingResult mine = service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 1)));

    CreateBookingResult theirs =
        service.create(command(OTHER_USER, KEY, new RequestedItem(VIP_ZONE, 1)));

    assertThat(theirs.created()).isTrue();
    assertThat(theirs.booking().id()).isNotEqualTo(mine.booking().id());
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  @Test
  void should_fail_with_not_found_when_the_event_is_unknown() {
    CreateBookingCommand unknown =
        new CreateBookingCommand(USER, KEY, uuid(999), List.of(new RequestedItem(VIP_ZONE, 1)));

    assertThatThrownBy(() -> service.create(unknown)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void should_fail_with_not_found_when_only_the_cancellation_of_the_event_is_known() {
    env.projections.save(EventProjection.cancelledTombstone(uuid(999), NOW));
    CreateBookingCommand tombstone =
        new CreateBookingCommand(USER, KEY, uuid(999), List.of(new RequestedItem(VIP_ZONE, 1)));

    assertThatThrownBy(() -> service.create(tombstone)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void should_refuse_a_cancelled_event_as_not_on_sale() {
    EventProjection cancelled = env.projections.findById(EVENT).orElseThrow();
    cancelled.applyCancelled(NOW);
    env.projections.save(cancelled);

    assertThatThrownBy(() -> service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 1))))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.EVENT_NOT_ON_SALE));
    assertThat(env.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_refuse_more_tickets_than_the_policy_allows_without_holding_any() {
    assertThatThrownBy(() -> service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 7))))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.MAX_TICKETS_EXCEEDED));
    assertThat(env.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_store_nothing_and_publish_nothing_when_a_zone_is_short() {
    env.inventory.reserve(EVENT, List.of(new RequestedItem(GENERAL_ZONE, 100)));

    assertThatThrownBy(() ->
            service.create(
                command(
                    USER, KEY, new RequestedItem(VIP_ZONE, 2), new RequestedItem(GENERAL_ZONE, 1))))
        .isInstanceOf(InsufficientInventoryException.class);

    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.bookings.findByIdempotencyKey(USER, KEY)).isEmpty();
    assertThat(env.publisher.events()).isEmpty();
  }

  @Test
  void should_let_a_failed_attempt_be_retried_with_the_same_key_once_tickets_are_free() {
    env.inventory.reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 20)));
    CreateBookingCommand command = command(USER, KEY, new RequestedItem(VIP_ZONE, 2));
    assertThatThrownBy(() -> service.create(command))
        .isInstanceOf(InsufficientInventoryException.class);
    env.inventory.release(EVENT, List.of(new RequestedItem(VIP_ZONE, 20)));

    CreateBookingResult retry = service.create(command);

    assertThat(retry.created()).isTrue();
  }

  /**
   * Another request stores a booking with the same key between this one's lookup and its insert.
   * The loser must not keep its tickets and must answer with the winner's booking.
   */
  @Test
  void
      should_answer_with_the_winner_and_hold_nothing_when_a_concurrent_request_got_the_key_first() {
    String fingerprint = RequestFingerprint.of(EVENT, List.of(new RequestedItem(VIP_ZONE, 2)));
    Booking winner =
        env.bookings.saveNew(
            newBooking(uuid(2000), USER, env.clock, new RequestedItem(VIP_ZONE, 2)),
            new IdempotencyRecord(USER, KEY, fingerprint));
    BookingRepository racing = new StaleLookupBookingRepository(env.bookings);

    CreateBookingResult result =
        service(racing).create(command(USER, KEY, new RequestedItem(VIP_ZONE, 2)));

    assertThat(result.created()).isFalse();
    assertThat(result.booking().id()).isEqualTo(winner.id());
    assertThat(env.reserved(VIP_ZONE)).isZero();
    assertThat(env.publisher.events()).isEmpty();
  }

  @Test
  void should_create_exactly_one_booking_and_hold_once_when_the_same_key_arrives_in_parallel()
      throws Exception {
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<CreateBookingResult> results;
    try {
      List<Future<CreateBookingResult>> futures =
          java.util.stream.IntStream.range(0, threads)
              .mapToObj(i ->
                  pool.submit(() -> {
                    start.await();
                    return service.create(command(USER, KEY, new RequestedItem(VIP_ZONE, 2)));
                  }))
              .toList();
      start.countDown();
      results = new java.util.ArrayList<>();
      for (Future<CreateBookingResult> future : futures) {
        results.add(future.get());
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(results).filteredOn(CreateBookingResult::created).hasSize(1);
    assertThat(results)
        .extracting(r -> r.booking().id())
        .containsOnly(results.get(0).booking().id());
    assertThat(env.reserved(VIP_ZONE)).isEqualTo(2);
  }

  /**
   * Hides the winner on the first lookup, as if the other transaction had not committed yet when
   * this one looked; the insert then collides with it.
   */
  private static final class StaleLookupBookingRepository implements BookingRepository {
    private final BookingRepository delegate;
    private boolean looked;

    StaleLookupBookingRepository(BookingRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public Booking saveNew(Booking booking, IdempotencyRecord idempotency) {
      return delegate.saveNew(booking, idempotency);
    }

    @Override
    public Optional<StoredBooking> findByIdempotencyKey(UUID userId, UUID key) {
      if (!looked) {
        looked = true;
        return Optional.empty();
      }
      return delegate.findByIdempotencyKey(userId, key);
    }

    @Override
    public Booking save(Booking booking) {
      return delegate.save(booking);
    }

    @Override
    public Optional<Booking> findById(UUID id) {
      return delegate.findById(id);
    }

    @Override
    public com.eventhub.booking.application.Page<Booking> findByUser(
        UUID userId,
        Optional<BookingStatus> status,
        com.eventhub.booking.application.PageQuery page) {
      return delegate.findByUser(userId, status, page);
    }

    @Override
    public List<Booking> findExpirable(Instant now, int limit) {
      return delegate.findExpirable(now, limit);
    }

    @Override
    public List<UUID> findActiveIdsByEvent(UUID eventId, int limit) {
      return delegate.findActiveIdsByEvent(eventId, limit);
    }
  }
}
