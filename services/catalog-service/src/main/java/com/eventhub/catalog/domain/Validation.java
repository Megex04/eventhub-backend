package com.eventhub.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** Field-level checks shared by the aggregate and its value objects. */
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

  static String text(String field, String value, int min, int max) {
    String stripped = required(field, value).strip();
    if (stripped.length() < min || stripped.length() > max) {
      throw new InvalidValueException(
          field, "%s length must be between %d and %d".formatted(field, min, max));
    }
    return stripped;
  }

  static String optionalText(String field, String value, int max) {
    return text(field, value == null ? "" : value, 0, max);
  }

  static void period(Instant startsAt, Instant endsAt) {
    required("startsAt", startsAt);
    required("endsAt", endsAt);
    if (!endsAt.isAfter(startsAt)) {
      throw new InvalidValueException("endsAt", "endsAt must be after startsAt");
    }
  }
}
