package com.eventhub.catalog.domain;

/**
 * Lifecycle: DRAFT -> PUBLISHED -> CANCELLED (terminal). FINISHED is part of the contract but no
 * transition reaches it yet.
 */
public enum EventStatus {
  DRAFT,
  PUBLISHED,
  CANCELLED,
  FINISHED
}
