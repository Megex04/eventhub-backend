package com.eventhub.booking.support;

import com.eventhub.booking.application.port.out.TransactionRunner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Decorates the real transaction runner of one {@code PayBookingService} so a test can stop (or
 * kill) that pay between its steps, deterministically. {@code pay} opens its transactions in a
 * fixed order: the first records the intention to charge, the second settles the outcome. Gating
 * the second one holds the pay exactly after the gateway answered and before the answer is
 * recorded; crashing it is a process that died in that window.
 */
public final class StepGatedTransactionRunner implements TransactionRunner {

  /** What a crashed process looks like from the test: nothing is recorded after the charge. */
  public static final class ProcessCrashed extends RuntimeException {
    public ProcessCrashed() {
      super("The process died after the charge was approved");
    }
  }

  private final TransactionRunner delegate;
  private final int gatedExecution;
  private final boolean crash;
  private final AtomicInteger executions = new AtomicInteger();
  private final CountDownLatch arrived = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);

  private StepGatedTransactionRunner(
      TransactionRunner delegate, int gatedExecution, boolean crash) {
    this.delegate = delegate;
    this.gatedExecution = gatedExecution;
    this.crash = crash;
  }

  /** Holds the settle step (the second transaction) until {@link #release()}. */
  public static StepGatedTransactionRunner holdingTheSettleStep(TransactionRunner delegate) {
    return new StepGatedTransactionRunner(delegate, 2, false);
  }

  /** Dies at the settle step, as a crashed process would. */
  public static StepGatedTransactionRunner crashingAtTheSettleStep(TransactionRunner delegate) {
    return new StepGatedTransactionRunner(delegate, 2, true);
  }

  public void awaitArrival() {
    await(arrived);
  }

  public void release() {
    released.countDown();
  }

  @Override
  public <T> T execute(Supplier<T> work) {
    if (executions.incrementAndGet() == gatedExecution) {
      if (crash) {
        throw new ProcessCrashed();
      }
      arrived.countDown();
      await(released);
    }
    return delegate.execute(work);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("The test never released the step");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
