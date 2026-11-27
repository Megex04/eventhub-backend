package com.eventhub.booking.application;

import java.util.List;

/** One page of results; {@code page} is zero-based. */
public record Page<T>(List<T> content, int page, int size, long totalElements) {

  public Page {
    content = List.copyOf(content);
  }

  public int totalPages() {
    return (int) ((totalElements + size - 1) / size);
  }
}
