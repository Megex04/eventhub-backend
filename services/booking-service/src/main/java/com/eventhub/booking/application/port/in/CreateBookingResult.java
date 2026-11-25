package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.Booking;

/** {@code created} is false when the Idempotency-Key had already produced this booking. */
public record CreateBookingResult(Booking booking, boolean created) {}
