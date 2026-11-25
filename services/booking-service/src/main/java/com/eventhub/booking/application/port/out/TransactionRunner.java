package com.eventhub.booking.application.port.out;

import java.util.function.Supplier;

/**
 * Runs a block in one database transaction. A port instead of {@code @Transactional} because the
 * payment saga needs several short transactions inside one use case, with the gateway call between
 * them and outside any transaction. An exception rolls the block back.
 */
public interface TransactionRunner {

  <T> T execute(Supplier<T> work);

  default void run(Runnable work) {
    execute(() -> {
      work.run();
      return null;
    });
  }
}
