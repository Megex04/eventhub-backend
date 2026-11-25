package com.eventhub.booking.application.port.out;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A payment gateway the test drives. It approves every token except {@code tok_declined}, and, like
 * a real one, is idempotent by key: a second charge (or refund) with the same {@code paymentId}
 * returns the first outcome and moves no money again.
 *
 * <p>Nothing here sleeps. A test pauses a call with a {@link Gate}: the call announces it arrived
 * and then waits until the test opens the gate, so the test can run whatever it wants while the
 * charge is "in flight".
 */
public final class FakePaymentGateway implements PaymentGateway {

  private static final long WAIT_SECONDS = 10;

  /** Holds the calls that reach it until {@link #open()}. */
  public static final class Gate {
    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    void pass() {
      arrived.countDown();
      await(proceed);
    }

    /** Blocks the test until a call is held by the gate. */
    public void awaitArrival() {
      await(arrived);
    }

    public void open() {
      proceed.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("The test never released the gate");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  private final Map<UUID, ChargeResult> charges = new ConcurrentHashMap<>();
  private final Set<UUID> refunded = ConcurrentHashMap.newKeySet();
  private final Set<UUID> approved = ConcurrentHashMap.newKeySet();
  private final AtomicInteger chargeCalls = new AtomicInteger();
  private final AtomicInteger refundCalls = new AtomicInteger();
  private final AtomicInteger failChargesBefore = new AtomicInteger();
  private final AtomicInteger failChargesAfter = new AtomicInteger();
  private final AtomicInteger failRefunds = new AtomicInteger();
  private volatile Gate chargeGate;
  private volatile Gate refundGate;
  private volatile boolean alwaysFailRefunds;

  public Gate holdCharges() {
    Gate gate = new Gate();
    this.chargeGate = gate;
    return gate;
  }

  public Gate holdRefunds() {
    Gate gate = new Gate();
    this.refundGate = gate;
    return gate;
  }

  /** The next {@code n} charges fail before the gateway did anything: no money moved. */
  public void failNextChargesBeforeProcessing(int n) {
    failChargesBefore.set(n);
  }

  /**
   * The next {@code n} charges are processed but the answer is lost: money moved, caller unsure.
   */
  public void failNextChargesAfterProcessing(int n) {
    failChargesAfter.set(n);
  }

  public void failNextRefunds(int n) {
    failRefunds.set(n);
  }

  public void failAllRefunds(boolean fail) {
    this.alwaysFailRefunds = fail;
  }

  @Override
  public ChargeResult charge(ChargeRequest request) {
    chargeCalls.incrementAndGet();
    Gate gate = chargeGate;
    if (gate != null) {
      gate.pass();
    }
    if (failChargesBefore.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new PaymentGatewayException("Gateway timeout before processing");
    }
    ChargeResult result =
        charges.computeIfAbsent(
            request.paymentId(),
            id -> {
              boolean declined = request.paymentToken().equals("tok_declined");
              if (!declined) {
                approved.add(id);
              }
              return declined
                  ? new ChargeResult.Declined("card_declined")
                  : new ChargeResult.Approved();
            });
    if (failChargesAfter.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new PaymentGatewayException("Gateway timeout after processing");
    }
    return result;
  }

  @Override
  public void refund(RefundRequest request) {
    refundCalls.incrementAndGet();
    Gate gate = refundGate;
    if (gate != null) {
      gate.pass();
    }
    if (alwaysFailRefunds || failRefunds.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new PaymentGatewayException("Gateway refused to refund");
    }
    if (!approved.contains(request.paymentId())) {
      throw new IllegalStateException("Refund of a payment that was never charged");
    }
    refunded.add(request.paymentId());
  }

  public int chargeCalls() {
    return chargeCalls.get();
  }

  public int refundCalls() {
    return refundCalls.get();
  }

  /** Payments that were really charged (once each, however many times the call was repeated). */
  public int distinctCharges() {
    return approved.size();
  }

  public int distinctRefunds() {
    return refunded.size();
  }

  public boolean wasRefunded(UUID paymentId) {
    return refunded.contains(paymentId);
  }

  /** Money the gateway currently holds: charged and not refunded. */
  public boolean holds(UUID paymentId) {
    return approved.contains(paymentId) && !refunded.contains(paymentId);
  }
}
