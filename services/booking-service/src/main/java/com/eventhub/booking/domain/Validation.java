package com.eventhub.booking.domain;

import java.util.UUID;

/** Field-level checks shared by the aggregates and their value objects. */
final class Validation {

  private Validation() {}

  static <T> T required(String field, T value) {
    if (value == null) {
      throw new InvalidValueException(field, field + " is required");
    }
    return value;
  }

  static UUID requiredId(String field, UUID value) {
    return required(field, value);
  }

  static int atLeast(String field, int value, int minimum) {
    if (value < minimum) {
      throw new InvalidValueException(field, "%s must be at least %d".formatted(field, minimum));
    }
    return value;
  }
}
