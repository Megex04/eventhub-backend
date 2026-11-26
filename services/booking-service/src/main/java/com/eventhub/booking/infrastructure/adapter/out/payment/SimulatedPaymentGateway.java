package com.eventhub.booking.infrastructure.adapter.out.payment;

import com.eventhub.booking.application.port.out.ChargeRequest;
import com.eventhub.booking.application.port.out.ChargeResult;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.RefundRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The simulated payment provider: it approves every token except {@code tok_declined}, and, like a
 * real one, is idempotent by key (a repeated charge or refund returns the first outcome and moves
 * no money again). It exists only in the {@code local}, {@code dev} and {@code test} profiles: in
 * any other profile the application refuses to start (see {@code NoRealPaymentGatewayConfig}).
 * Tokens are opaque and are never logged.
 */
@Component
@Profile({"local", "dev", "test"})
public class SimulatedPaymentGateway implements PaymentGateway {

  static final String DECLINED_TOKEN = "tok_declined";

  private final Map<UUID, ChargeResult> charges = new ConcurrentHashMap<>();
  private final Set<UUID> refunds = ConcurrentHashMap.newKeySet();

  @Override
  public ChargeResult charge(ChargeRequest request) {
    return charges.computeIfAbsent(
        request.paymentId(),
        id ->
            DECLINED_TOKEN.equals(request.paymentToken())
                ? new ChargeResult.Declined("card_declined")
                : new ChargeResult.Approved());
  }

  @Override
  public void refund(RefundRequest request) {
    refunds.add(request.paymentId());
  }
}
