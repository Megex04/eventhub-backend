package com.eventhub.booking.domain;

import static com.eventhub.booking.domain.BookingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The status transitions of the booking, checked as a whole matrix: every (status, action) pair is
 * either a legal move listed in {@link #LEGAL} or must raise {@link InvalidStateException}. Each
 * action runs at the instant where its time precondition holds, so only the status can refuse.
 */
class BookingStateMachineTest {

  private enum Action {
    PAY(NOW.plusSeconds(1), (b, clock) -> b.beginPayment(NEXT_PAYMENT, TOKEN, clock)),
    EXPIRE(EXPIRES_AT, (b, clock) -> b.expire(clock)),
    CANCEL_BY_USER(
        NOW.plusSeconds(1), (b, clock) -> b.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, clock)),
    CANCEL_BY_EVENT(
        NOW.plusSeconds(1),
        (b, clock) -> b.cancel(CancelReason.EVENT_CANCELLED, EVENT_STARTS, clock));

    final Instant when;
    final BiConsumer<Booking, Clock> run;

    Action(Instant when, BiConsumer<Booking, Clock> run) {
      this.when = when;
      this.run = run;
    }
  }

  private static final Map<BookingStatus, Set<Action>> LEGAL = new EnumMap<>(BookingStatus.class);

  static {
    LEGAL.put(
        BookingStatus.PENDING,
        Set.of(Action.PAY, Action.EXPIRE, Action.CANCEL_BY_USER, Action.CANCEL_BY_EVENT));
    LEGAL.put(BookingStatus.CONFIRMED, Set.of(Action.CANCEL_BY_USER, Action.CANCEL_BY_EVENT));
    LEGAL.put(BookingStatus.EXPIRED, Set.of());
    LEGAL.put(BookingStatus.CANCELLED, Set.of());
  }

  private static Booking bookingIn(BookingStatus status) {
    return restored(status);
  }

  static Stream<Arguments> allCombinations() {
    return Stream.of(BookingStatus.values())
        .flatMap(status -> Stream.of(Action.values()).map(action -> Arguments.of(status, action)));
  }

  static Stream<Arguments> illegalCombinations() {
    return allCombinations()
        .filter(args -> !LEGAL.get((BookingStatus) args.get()[0]).contains(args.get()[1]));
  }

  static Stream<Arguments> legalCombinations() {
    return allCombinations()
        .filter(args -> LEGAL.get((BookingStatus) args.get()[0]).contains(args.get()[1]));
  }

  @ParameterizedTest(name = "{0} cannot {1}")
  @MethodSource("illegalCombinations")
  void should_throw_invalid_state_when_the_action_is_not_allowed_in_that_status(
      BookingStatus status, Action action) {
    Booking booking = bookingIn(status);

    assertThatThrownBy(() -> action.run.accept(booking, at(action.when)))
        .isInstanceOf(InvalidStateException.class)
        .hasFieldOrPropertyWithValue("currentStatus", status.name());
    assertThat(booking.status()).isEqualTo(status);
    assertThat(booking.pullDomainEvents()).isEmpty();
  }

  @ParameterizedTest(name = "{0} can {1}")
  @MethodSource("legalCombinations")
  void should_accept_the_action_when_it_is_allowed_in_that_status(
      BookingStatus status, Action action) {
    Booking booking = bookingIn(status);

    action.run.accept(booking, at(action.when));
  }

  @ParameterizedTest(name = "{0} is terminal: {1} stays refused")
  @MethodSource("terminalCombinations")
  void should_keep_terminal_statuses_terminal(BookingStatus status, Action action) {
    Booking booking = bookingIn(status);

    assertThatThrownBy(() -> action.run.accept(booking, at(action.when)))
        .isInstanceOf(InvalidStateException.class);
  }

  static Stream<Arguments> terminalCombinations() {
    return allCombinations()
        .filter(args ->
            args.get()[0] == BookingStatus.EXPIRED || args.get()[0] == BookingStatus.CANCELLED);
  }
}
