package com.eventhub.booking.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Starts N tasks at the same instant and collects what each returned or threw. No sleeps. */
public final class Concurrently {

  /** What one task did: a value, or the exception it ended with. */
  public record Outcome<T>(T value, Throwable failure) {}

  private static final long TIMEOUT_SECONDS = 180;

  private Concurrently() {}

  public static <T> List<Outcome<T>> run(int threads, IntFunction<Callable<T>> tasks)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Outcome<T>>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<T> task = tasks.apply(i);
        futures.add(
            pool.submit(() -> {
              start.await();
              try {
                return new Outcome<>(task.call(), null);
              } catch (Throwable failure) {
                return new Outcome<>(null, failure);
              }
            }));
      }
      start.countDown();
      List<Outcome<T>> outcomes = new ArrayList<>();
      for (Future<Outcome<T>> future : futures) {
        outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }
}
