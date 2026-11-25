package com.eventhub.booking.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared builders for the Booking tests: one event with two PEN zones and a 10 minute hold. */
final class BookingFixtures {

  static final UUID BOOKING = uuid(1);
  static final UUID USER = uuid(2);
  static final UUID EVENT = uuid(3);
  static final UUID VIP_ZONE = uuid(4);
  static final UUID GENERAL_ZONE = uuid(5);
  static final UUID PAYMENT = uuid(6);
  static final UUID NEXT_PAYMENT = uuid(7);

  static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
  static final Instant EVENT_STARTS = Instant.parse("2027-01-10T20:00:00Z");
  static final Duration HOLD = Duration.ofMinutes(10);
  static final Instant EXPIRES_AT = NOW.plus(HOLD);

  static final BookingPolicy POLICY = new BookingPolicy(HOLD, 6);
  static final ProjectedZone VIP =
      new ProjectedZone(VIP_ZONE, "VIP", Money.of("150.00", "PEN"), 20);
  static final ProjectedZone GENERAL =
      new ProjectedZone(GENERAL_ZONE, "General", Money.of("50.00", "PEN"), 100);

  private BookingFixtures() {}

  static UUID uuid(int n) {
    return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(n));
  }

  static Clock at(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  static EventProjection projection(ProjectedZone... zones) {
    return EventProjection.published(
        EVENT, EVENT_STARTS, EVENT_STARTS.plus(Duration.ofHours(3)), List.of(zones), NOW);
  }

  static EventProjection openEvent() {
    return projection(VIP, GENERAL);
  }

  /** Two VIP tickets (300.00 PEN), created at {@link #NOW}. */
  static Booking pendingBooking() {
    return Booking.create(
        BOOKING, USER, openEvent(), List.of(new RequestedItem(VIP_ZONE, 2)), POLICY, at(NOW));
  }

  static Money total() {
    return Money.of("300.00", "PEN");
  }

  /** Restores a booking in {@code status}; a CONFIRMED one carries its CAPTURED payment. */
  static Booking restored(BookingStatus status) {
    Payment captured = Payment.restore(PAYMENT, PaymentStatus.CAPTURED, total(), Optional.empty());
    return Booking.restore(
        BOOKING,
        USER,
        EVENT,
        status,
        List.of(new BookingItem(VIP_ZONE, "VIP", 2, VIP.price())),
        total(),
        NOW,
        EXPIRES_AT,
        Optional.ofNullable(status == BookingStatus.CONFIRMED ? NOW.plusSeconds(60) : null),
        Optional.ofNullable(status == BookingStatus.EXPIRED ? EXPIRES_AT : null),
        Optional.ofNullable(status == BookingStatus.CANCELLED ? NOW.plusSeconds(30) : null),
        Optional.ofNullable(status == BookingStatus.CANCELLED ? CancelReason.USER_REQUEST : null),
        Optional.ofNullable(status == BookingStatus.CONFIRMED ? captured : null),
        4L);
  }
}
