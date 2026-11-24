package com.eventhub.catalog.application.query;

import com.eventhub.catalog.domain.InvalidValueException;

/** Zero-based page index and size (1..100), matching the OpenAPI contract. */
public record PageRequest(int page, int size) {

  public static final int MAX_SIZE = 100;

  public PageRequest {
    if (page < 0) {
      throw new InvalidValueException("page", "page must not be negative");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new InvalidValueException("size", "size must be between 1 and %d".formatted(MAX_SIZE));
    }
  }
}
