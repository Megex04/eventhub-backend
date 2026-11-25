package com.eventhub.booking.application.service;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.application.exception.NotFoundException;
import com.eventhub.booking.application.port.in.EventAvailability;
import com.eventhub.booking.application.port.in.ListBookingsQuery;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingQueriesTest {

  private final AppEnv env = new AppEnv().withOpenEvent();
  private final GetBookingService get = new GetBookingService(env.bookings);
  private final ListMyBookingsService list = new ListMyBookingsService(env.bookings);
  private final GetAvailabilityService availability =
      new GetAvailabilityService(env.projections, env.inventory, env.clock);

  private Booking stored(long id, UUID userId) {
    Booking booking = newBooking(uuid(id), userId, env.clock, new RequestedItem(VIP_ZONE, 1));
    return env.bookings.saveNew(
        booking,
        new com.eventhub.booking.application.port.out.IdempotencyRecord(
            userId, uuid(id + 5000), "fp"));
  }

  @Test
  void should_return_the_booking_to_its_owner() {
    stored(1, USER);

    assertThat(get.get(USER, uuid(1)).id()).isEqualTo(uuid(1));
  }

  @Test
  void should_answer_not_found_when_the_booking_belongs_to_someone_else() {
    stored(1, OTHER_USER);

    assertThatThrownBy(() -> get.get(USER, uuid(1))).isInstanceOf(NotFoundException.class);
  }

  @Test
  void should_answer_not_found_when_the_booking_does_not_exist() {
    assertThatThrownBy(() -> get.get(USER, uuid(99))).isInstanceOf(NotFoundException.class);
  }

  @Test
  void should_list_only_the_bookings_of_the_caller_with_the_paging_requested() {
    stored(1, USER);
    env.clock.advance(Duration.ofSeconds(1));
    stored(2, USER);
    stored(3, OTHER_USER);

    Page<Booking> page =
        list.list(new ListBookingsQuery(USER, Optional.empty(), new PageQuery(0, 1, true)));

    assertThat(page.content()).extracting(Booking::id).containsExactly(uuid(2));
    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.totalPages()).isEqualTo(2);
  }

  @Test
  void should_filter_the_list_by_status() {
    stored(1, USER);
    Booking second = stored(2, USER);
    second.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, env.clock);
    env.bookings.save(second);

    Page<Booking> page =
        list.list(
            new ListBookingsQuery(
                USER, Optional.of(BookingStatus.CANCELLED), new PageQuery(0, 10, true)));

    assertThat(page.content()).extracting(Booking::id).containsExactly(uuid(2));
  }

  @Test
  void should_show_what_is_free_in_each_zone() {
    env.inventory.reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 5)));

    EventAvailability result = availability.get(EVENT);

    assertThat(result.onSale()).isTrue();
    assertThat(result.startsAt()).isEqualTo(EVENT_STARTS);
    assertThat(result.zones())
        .extracting(
            EventAvailability.ZoneAvailability::name,
            EventAvailability.ZoneAvailability::capacity,
            EventAvailability.ZoneAvailability::available)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("VIP", 20, 15),
            org.assertj.core.groups.Tuple.tuple("General", 100, 100));
    assertThat(result.zones())
        .filteredOn(zone -> zone.name().equals("VIP"))
        .singleElement()
        .satisfies(zone -> assertThat(zone.price()).isEqualTo(VIP.price()));
  }

  @Test
  void should_report_the_event_as_not_on_sale_once_it_started() {
    env.clock.set(EVENT_STARTS);

    assertThat(availability.get(EVENT).onSale()).isFalse();
  }

  @Test
  void should_report_a_cancelled_event_as_not_on_sale_and_keep_showing_its_zones() {
    EventProjection cancelled = env.projections.findById(EVENT).orElseThrow();
    cancelled.applyCancelled(NOW.plusSeconds(1));
    env.projections.save(cancelled);

    EventAvailability result = availability.get(EVENT);

    assertThat(result.onSale()).isFalse();
    assertThat(result.zones()).hasSize(2);
  }

  @Test
  void should_answer_not_found_for_an_unknown_event() {
    assertThatThrownBy(() -> availability.get(uuid(999))).isInstanceOf(NotFoundException.class);
  }

  @Test
  void should_answer_not_found_for_an_event_known_only_as_cancelled() {
    env.projections.save(EventProjection.cancelledTombstone(uuid(999), NOW));

    assertThatThrownBy(() -> availability.get(uuid(999))).isInstanceOf(NotFoundException.class);
  }
}
