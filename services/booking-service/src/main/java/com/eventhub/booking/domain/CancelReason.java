package com.eventhub.booking.domain;

/** Why a booking was cancelled; the values of the {@code BookingCancelled} event. */
public enum CancelReason {
  USER_REQUEST,
  EVENT_CANCELLED
}
