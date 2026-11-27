package com.eventhub.booking.application;

/** Which page to read and in which order of {@code createdAt}. */
public record PageQuery(int page, int size, boolean newestFirst) {

  public PageQuery {
    if (page < 0 || size < 1) {
      throw new IllegalArgumentException("page must be >= 0 and size >= 1");
    }
  }
}
