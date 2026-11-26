package com.eventhub.booking.infrastructure.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.eventhub.booking.application.port.out.ChargeRequest;
import com.eventhub.booking.application.port.out.ChargeResult;
import com.eventhub.booking.application.port.out.RefundRequest;
import com.eventhub.booking.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatedPaymentGatewayTest {

  private static final Money TEN = Money.of("10.00", "PEN");

  private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

  @Test
  void should_approve_any_token_but_the_declined_one() {
    assertThat(gateway.charge(new ChargeRequest(UUID.randomUUID(), TEN, "tok_approved")))
        .isInstanceOf(ChargeResult.Approved.class);
    assertThat(gateway.charge(new ChargeRequest(UUID.randomUUID(), TEN, "tok_anything_else")))
        .isInstanceOf(ChargeResult.Approved.class);
    assertThat(gateway.charge(new ChargeRequest(UUID.randomUUID(), TEN, "tok_declined")))
        .isInstanceOf(ChargeResult.Declined.class);
  }

  @Test
  void should_answer_a_repeated_charge_with_the_first_outcome_whatever_the_token() {
    UUID key = UUID.randomUUID();
    ChargeResult first = gateway.charge(new ChargeRequest(key, TEN, "tok_declined"));

    assertThat(gateway.charge(new ChargeRequest(key, TEN, "tok_approved"))).isEqualTo(first);
  }

  @Test
  void should_accept_a_refund_repeated_with_the_same_key() {
    UUID key = UUID.randomUUID();

    assertThatCode(
            () -> {
              gateway.refund(new RefundRequest(key, TEN));
              gateway.refund(new RefundRequest(key, TEN));
            })
        .doesNotThrowAnyException();
  }
}
