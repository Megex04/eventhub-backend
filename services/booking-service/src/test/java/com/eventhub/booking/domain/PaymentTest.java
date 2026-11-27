package com.eventhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentTest {

  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
  private static final Money AMOUNT = Money.of("150.00", "PEN");
  private static final String TOKEN = "tok_approved";

  private enum Action {
    CAPTURE(p -> p.capture()),
    DECLINE(p -> p.decline()),
    REQUEST_REFUND(p -> p.requestRefund(RefundReason.BOOKING_CANCELLED)),
    COMPLETE_REFUND(p -> p.completeRefund());

    final UnaryOperator<Payment> apply;

    Action(UnaryOperator<Payment> apply) {
      this.apply = apply;
    }
  }

  /**
   * The only legal moves: INITIATED -> CAPTURED | DECLINED, CAPTURED -> REFUND_PENDING -> REFUNDED.
   */
  private static final Map<PaymentStatus, Map<Action, PaymentStatus>> LEGAL =
      Map.of(
          PaymentStatus.INITIATED,
              Map.of(
                  Action.CAPTURE, PaymentStatus.CAPTURED,
                  Action.DECLINE, PaymentStatus.DECLINED),
          PaymentStatus.CAPTURED, Map.of(Action.REQUEST_REFUND, PaymentStatus.REFUND_PENDING),
          PaymentStatus.REFUND_PENDING, Map.of(Action.COMPLETE_REFUND, PaymentStatus.REFUNDED),
          PaymentStatus.DECLINED, Map.of(),
          PaymentStatus.REFUNDED, Map.of());

  private static Payment in(PaymentStatus status) {
    return Payment.restore(ID, status, AMOUNT, TOKEN, Optional.empty());
  }

  static Stream<Arguments> illegalMoves() {
    return Stream.of(PaymentStatus.values())
        .flatMap(status ->
            Stream.of(Action.values())
                .filter(action -> !LEGAL.get(status).containsKey(action))
                .map(action -> Arguments.of(status, action)));
  }

  static Stream<Arguments> legalMoves() {
    return LEGAL.entrySet().stream()
        .flatMap(from ->
            from.getValue().entrySet().stream()
                .map(move -> Arguments.of(from.getKey(), move.getKey(), move.getValue())));
  }

  @Test
  void should_start_initiated_with_the_given_amount() {
    Payment payment = Payment.initiate(ID, AMOUNT, TOKEN);

    assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATED);
    assertThat(payment.id()).isEqualTo(ID);
    assertThat(payment.amount()).isEqualTo(AMOUNT);
    assertThat(payment.paymentToken()).contains(TOKEN);
    assertThat(payment.refundReason()).isEmpty();
  }

  @ParameterizedTest(name = "{0} --{1}--> {2}")
  @MethodSource("legalMoves")
  void should_move_to_the_next_status_when_the_transition_is_legal(
      PaymentStatus from, Action action, PaymentStatus to) {
    assertThat(action.apply.apply(in(from)).status()).isEqualTo(to);
  }

  @ParameterizedTest(name = "{0} cannot {1}")
  @MethodSource("illegalMoves")
  void should_reject_the_transition_when_it_is_not_legal(PaymentStatus from, Action action) {
    assertThatThrownBy(() -> action.apply.apply(in(from)))
        .isInstanceOf(InvalidStateException.class)
        .hasFieldOrPropertyWithValue("currentStatus", from.name());
  }

  @Test
  void should_keep_the_reason_and_the_amount_when_a_refund_is_requested() {
    Payment pending = in(PaymentStatus.CAPTURED).requestRefund(RefundReason.EVENT_CANCELLED);

    assertThat(pending.refundReason()).contains(RefundReason.EVENT_CANCELLED);
    assertThat(pending.amount()).isEqualTo(AMOUNT);
    assertThat(pending.completeRefund().refundReason()).contains(RefundReason.EVENT_CANCELLED);
  }

  @Test
  void should_erase_the_token_when_the_charge_is_captured() {
    assertThat(Payment.initiate(ID, AMOUNT, TOKEN).capture().paymentToken()).isEmpty();
  }

  @Test
  void should_erase_the_token_when_the_charge_is_declined() {
    assertThat(Payment.initiate(ID, AMOUNT, TOKEN).decline().paymentToken()).isEmpty();
  }

  @Test
  void should_keep_the_token_while_the_payment_is_initiated() {
    assertThat(Payment.initiate(ID, AMOUNT, TOKEN).paymentToken()).contains(TOKEN);
  }

  @Test
  void should_never_have_a_token_once_the_money_is_refunded() {
    Payment refunded =
        Payment.initiate(ID, AMOUNT, TOKEN)
            .capture()
            .requestRefund(RefundReason.BOOKING_CANCELLED)
            .completeRefund();

    assertThat(refunded.paymentToken()).isEmpty();
    assertThat(refunded.toString()).doesNotContain(TOKEN);
  }

  @Test
  void should_leave_the_original_untouched_when_it_transitions() {
    Payment initiated = Payment.initiate(ID, AMOUNT, TOKEN);

    initiated.capture();

    assertThat(initiated.status()).isEqualTo(PaymentStatus.INITIATED);
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"INITIATED", "CAPTURED", "REFUND_PENDING"})
  void should_count_as_active_when_it_blocks_a_new_attempt(PaymentStatus status) {
    assertThat(in(status).isActive()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"DECLINED", "REFUNDED"})
  void should_not_count_as_active_when_it_is_finished(PaymentStatus status) {
    assertThat(in(status).isActive()).isFalse();
  }

  @Test
  void should_reject_a_missing_refund_reason() {
    assertThatThrownBy(() -> in(PaymentStatus.CAPTURED).requestRefund(null))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_a_missing_id_or_amount_when_initiated() {
    assertThatThrownBy(() -> Payment.initiate(null, AMOUNT, TOKEN))
        .isInstanceOf(InvalidValueException.class);
    assertThatThrownBy(() -> Payment.initiate(ID, null, TOKEN))
        .isInstanceOf(InvalidValueException.class);
  }
}
