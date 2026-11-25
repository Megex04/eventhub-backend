package com.eventhub.booking.application;

import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingPolicy;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.ProjectedZone;
import com.eventhub.booking.domain.RequestedItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Shared data of the application tests: one open event with a VIP and a General zone, in PEN. */
public final class AppFixtures {

  public static final UUID USER = uuid(10);
  public static final UUID OTHER_USER = uuid(11);
  public static final UUID EVENT = uuid(12);
  public static final UUID VIP_ZONE = uuid(13);
  public static final UUID GENERAL_ZONE = uuid(14);

  public static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
  public static final Instant EVENT_STARTS = Instant.parse("2027-01-10T20:00:00Z");
  public static final Duration HOLD = Duration.ofMinutes(10);
  public static final BookingPolicy POLICY = new BookingPolicy(HOLD, 6);

  public static final ProjectedZone VIP =
      new ProjectedZone(VIP_ZONE, "VIP", Money.of("150.00", "PEN"), 20);
  public static final ProjectedZone GENERAL =
      new ProjectedZone(GENERAL_ZONE, "General", Money.of("50.00", "PEN"), 100);

  private AppFixtures() {}

  public static UUID uuid(long n) {
    return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(n));
  }

  public static Clock at(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  public static EventProjection openProjection() {
    return EventProjection.published(
        EVENT, EVENT_STARTS, EVENT_STARTS.plus(Duration.ofHours(3)), List.of(VIP, GENERAL), NOW);
  }

  /** A PENDING booking of {@code userId} for the fixture event, created at the clock's instant. */
  public static Booking newBooking(UUID id, UUID userId, Clock clock, RequestedItem... items) {
    return Booking.create(id, userId, openProjection(), List.of(items), POLICY, clock);
  }
}
