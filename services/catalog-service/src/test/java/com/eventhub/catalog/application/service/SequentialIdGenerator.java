package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.port.out.IdGenerator;
import java.util.UUID;

/** Deterministic ids (0...01, 0...02, ...) so tests can assert exactly what was generated. */
final class SequentialIdGenerator implements IdGenerator {

  private long counter;

  @Override
  public UUID newId() {
    return new UUID(0L, ++counter);
  }

  static UUID idNumber(long n) {
    return new UUID(0L, n);
  }
}
