package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.out.ChargeRequest;
import com.eventhub.booking.application.port.out.ChargeResult;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.RefundRequest;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentStatus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps the fake gateway and records every broken rule of the saga at the moment of the call: the
 * gateway must never be called inside a transaction, and a charge must find its INITIATED payment
 * already stored (so a charge can never be left without a trace in our database).
 */
final class InstrumentedGateway implements PaymentGateway {

  private final AppEnv env;
  private final List<String> violations = new CopyOnWriteArrayList<>();

  InstrumentedGateway(AppEnv env) {
    this.env = env;
  }

  List<String> violations() {
    return violations;
  }

  @Override
  public ChargeResult charge(ChargeRequest request) {
    if (env.store.inTransaction()) {
      violations.add("charge called inside a transaction");
    }
    boolean traced =
        env.bookings.findByPaymentId(request.paymentId()).stream()
            .flatMap(booking -> booking.payment().stream())
            .anyMatch(payment ->
                payment.id().equals(request.paymentId())
                    && payment.status() == PaymentStatus.INITIATED
                    && payment.amount().equals(request.amount()));
    if (!traced) {
      violations.add("charge called before its INITIATED payment was stored");
    }
    return env.gateway.charge(request);
  }

  @Override
  public void refund(RefundRequest request) {
    if (env.store.inTransaction()) {
      violations.add("refund called inside a transaction");
    }
    env.gateway.refund(request);
  }

  /** The payment as stored, for assertions. */
  static Payment paymentOf(AppEnv env, java.util.UUID bookingId) {
    return env.booking(bookingId).payment().orElseThrow();
  }
}
