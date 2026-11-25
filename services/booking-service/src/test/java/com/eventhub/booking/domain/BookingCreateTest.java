package com.eventhub.booking.domain;

import static com.eventhub.booking.domain.BookingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.BusinessRuleViolationException.Rule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BookingCreateTest {

  private static Booking create(EventProjection event, RequestedItem... items) {
    return Booking.create(BOOKING, USER, event, List.of(items), POLICY, at(NOW));
  }

  private static void assertRule(Rule rule, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class, e -> assertThat(e.rule()).isEqualTo(rule));
  }

  @Test
  void should_create_a_pending_booking_priced_from_the_projection() {
    Booking booking =
        create(openEvent(), new RequestedItem(VIP_ZONE, 2), new RequestedItem(GENERAL_ZONE, 3));

    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
    assertThat(booking.userId()).isEqualTo(USER);
    assertThat(booking.eventId()).isEqualTo(EVENT);
    assertThat(booking.items())
        .containsExactly(
            new BookingItem(VIP_ZONE, "VIP", 2, VIP.price()),
            new BookingItem(GENERAL_ZONE, "General", 3, GENERAL.price()));
    assertThat(booking.total()).isEqualTo(Money.of("450.00", "PEN"));
    assertThat(booking.createdAt()).isEqualTo(NOW);
    assertThat(booking.expiresAt()).isEqualTo(NOW.plus(HOLD));
    assertThat(booking.payment()).isEmpty();
    assertThat(booking.version()).isZero();
  }

  @Test
  void should_expose_the_items_as_inventory_lines() {
    Booking booking =
        create(openEvent(), new RequestedItem(VIP_ZONE, 2), new RequestedItem(GENERAL_ZONE, 3));

    assertThat(booking.requestedItems())
        .containsExactly(new RequestedItem(VIP_ZONE, 2), new RequestedItem(GENERAL_ZONE, 3));
  }

  @Test
  void should_hold_for_the_duration_given_by_the_policy() {
    Booking booking =
        Booking.create(
            BOOKING,
            USER,
            openEvent(),
            List.of(new RequestedItem(VIP_ZONE, 1)),
            new BookingPolicy(Duration.ofMinutes(3), 6),
            at(NOW));

    assertThat(booking.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
  }

  @Test
  void should_record_a_booking_created_event() {
    Booking booking = create(openEvent(), new RequestedItem(VIP_ZONE, 2));

    assertThat(booking.pullDomainEvents())
        .containsExactly(
            new BookingCreated(
                BOOKING,
                USER,
                EVENT,
                List.of(new BookingItem(VIP_ZONE, "VIP", 2, VIP.price())),
                total(),
                EXPIRES_AT,
                NOW));
    assertThat(booking.pullDomainEvents()).isEmpty();
  }

  @Test
  void should_accept_exactly_the_maximum_number_of_tickets() {
    Booking booking =
        create(openEvent(), new RequestedItem(VIP_ZONE, 4), new RequestedItem(GENERAL_ZONE, 2));

    assertThat(booking.total()).isEqualTo(Money.of("700.00", "PEN"));
  }

  @Test
  void should_reject_one_ticket_over_the_maximum() {
    assertRule(
        Rule.MAX_TICKETS_EXCEEDED,
        () ->
            create(
                openEvent(), new RequestedItem(VIP_ZONE, 4), new RequestedItem(GENERAL_ZONE, 3)));
  }

  @Test
  void should_apply_the_maximum_of_the_policy_not_a_fixed_one() {
    assertRule(
        Rule.MAX_TICKETS_EXCEEDED,
        () ->
            Booking.create(
                BOOKING,
                USER,
                openEvent(),
                List.of(new RequestedItem(VIP_ZONE, 3)),
                new BookingPolicy(HOLD, 2),
                at(NOW)));
  }

  @Test
  void should_reject_a_zone_listed_twice() {
    assertRule(
        Rule.DUPLICATE_ZONE,
        () -> create(openEvent(), new RequestedItem(VIP_ZONE, 1), new RequestedItem(VIP_ZONE, 2)));
  }

  @Test
  void should_reject_a_zone_that_is_not_in_the_event() {
    assertRule(Rule.ZONE_NOT_IN_EVENT, () -> create(openEvent(), new RequestedItem(uuid(99), 1)));
  }

  @Test
  void should_reject_a_booking_for_a_cancelled_event() {
    EventProjection cancelled = openEvent();
    cancelled.applyCancelled(NOW.plusSeconds(1));

    assertRule(
        Rule.EVENT_NOT_ON_SALE,
        () ->
            Booking.create(
                BOOKING,
                USER,
                cancelled,
                List.of(new RequestedItem(VIP_ZONE, 1)),
                POLICY,
                at(NOW.plusSeconds(2))));
  }

  @Test
  void should_reject_a_booking_when_the_event_already_started() {
    assertRule(
        Rule.EVENT_NOT_ON_SALE,
        () ->
            Booking.create(
                BOOKING,
                USER,
                openEvent(),
                List.of(new RequestedItem(VIP_ZONE, 1)),
                POLICY,
                at(EVENT_STARTS)));
  }

  @Test
  void should_reject_zones_priced_in_different_currencies() {
    ProjectedZone dollars =
        new ProjectedZone(GENERAL_ZONE, "General", Money.of("20.00", "USD"), 50);

    assertRule(
        Rule.MIXED_CURRENCY,
        () ->
            create(
                projection(VIP, dollars),
                new RequestedItem(VIP_ZONE, 1),
                new RequestedItem(GENERAL_ZONE, 1)));
  }

  @Test
  void should_report_the_event_not_on_sale_before_any_other_rule() {
    EventProjection cancelled = openEvent();
    cancelled.applyCancelled(NOW.plusSeconds(1));

    assertRule(
        Rule.EVENT_NOT_ON_SALE,
        () ->
            Booking.create(
                BOOKING,
                USER,
                cancelled,
                List.of(new RequestedItem(uuid(99), 1), new RequestedItem(uuid(99), 1)),
                POLICY,
                at(NOW.plusSeconds(2))));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void should_reject_a_quantity_below_one_as_an_invalid_value(int quantity) {
    assertThatThrownBy(() -> new RequestedItem(VIP_ZONE, quantity))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "quantity");
  }

  @Test
  void should_reject_a_booking_without_items_as_an_invalid_value() {
    assertThatThrownBy(() -> create(openEvent()))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "items");
  }

  @Test
  void should_reject_missing_ids() {
    List<RequestedItem> items = List.of(new RequestedItem(VIP_ZONE, 1));

    assertThatThrownBy(() -> Booking.create(null, USER, openEvent(), items, POLICY, at(NOW)))
        .isInstanceOf(InvalidValueException.class);
    assertThatThrownBy(() -> Booking.create(BOOKING, null, openEvent(), items, POLICY, at(NOW)))
        .isInstanceOf(InvalidValueException.class);
  }
}
