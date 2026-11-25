package com.eventhub.booking.application.port.out;

import com.eventhub.booking.domain.Booking;

/** A booking together with the fingerprint of the request that created it. */
public record StoredBooking(Booking booking, String fingerprint) {}
