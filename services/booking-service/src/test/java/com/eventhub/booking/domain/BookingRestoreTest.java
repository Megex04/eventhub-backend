package com.eventhub.booking.domain;

import static com.eventhub.booking.domain.BookingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingRestoreTest {

  @Test
  void should_rebuild_the_exact_persisted_state() {
    Booking booking = restored(BookingStatus.CONFIRMED);

    assertThat(booking.id()).isEqualTo(BOOKING);
    assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.confirmedAt()).contains(NOW.plusSeconds(60));
    assertThat(booking.payment()).isPresent();
    assertThat(booking.total()).isEqualTo(total());
    assertThat(booking.version()).isEqualTo(4L);
  }

  @Test
  void should_not_record_any_event_when_restored() {
    assertThat(restored(BookingStatus.CONFIRMED).pullDomainEvents()).isEmpty();
    assertThat(restored(BookingStatus.PENDING).pullDomainEvents()).isEmpty();
  }

  @Test
  void should_not_revalidate_what_was_already_persisted() {
    Booking legacy =
        Booking.restore(
            BOOKING,
            USER,
            EVENT,
            BookingStatus.PENDING,
            List.of(),
            Money.of("0.00", "PEN"),
            NOW,
            EXPIRES_AT,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            0L);

    assertThat(legacy.items()).isEmpty();
  }

  @Test
  void should_keep_the_version_read_only_when_the_aggregate_changes() {
    Booking booking = restored(BookingStatus.PENDING);

    booking.cancel(CancelReason.USER_REQUEST, EVENT_STARTS, at(NOW.plusSeconds(1)));

    assertThat(booking.version()).isEqualTo(4L);
  }

  @Test
  void should_expose_immutable_collections() {
    Booking booking = restored(BookingStatus.PENDING);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> booking.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
