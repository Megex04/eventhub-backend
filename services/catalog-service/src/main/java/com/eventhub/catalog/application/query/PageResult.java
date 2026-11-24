package com.eventhub.catalog.application.query;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

  public PageResult {
    content = List.copyOf(content);
  }

  public int totalPages() {
    return (int) ((totalElements + size - 1) / size);
  }
}
