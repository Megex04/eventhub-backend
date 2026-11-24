package com.eventhub.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  @Test
  void should_normalize_scale_to_two_when_amount_has_fewer_decimals() {
    Money money = Money.of("150", "PEN");

    assertThat(money.amount()).isEqualByComparingTo("150.00");
    assertThat(money.amount().scale()).isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource({"10.005,10.00", "10.015,10.02", "10.025,10.02", "10.035,10.04", "0.001,0.00"})
  void should_round_half_even_when_amount_has_more_than_two_decimals(
      String input, String expected) {
    Money money = Money.of(input, "PEN");

    assertThat(money.amount()).isEqualTo(new BigDecimal(expected));
  }

  @Test
  void should_accept_zero_when_event_zone_is_free() {
    assertThat(Money.of("0.00", "PEN").amount()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void should_accept_maximum_amount_when_it_has_ten_integer_digits() {
    assertThat(Money.of("9999999999.99", "USD").amount())
        .isEqualTo(new BigDecimal("9999999999.99"));
  }

  @Test
  void should_reject_amount_when_it_exceeds_ten_integer_digits() {
    assertThatThrownBy(() -> Money.of("10000000000.00", "PEN"))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("amount"));
  }

  @Test
  void should_reject_amount_when_it_is_negative() {
    assertThatThrownBy(() -> Money.of("-0.01", "PEN"))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("amount"));
  }

  @Test
  void should_reject_amount_when_it_is_null() {
    assertThatThrownBy(() -> new Money(null, "PEN")).isInstanceOf(InvalidValueException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "", "1,50", "1e3x"})
  void should_reject_amount_when_text_is_not_a_decimal(String text) {
    assertThatThrownBy(() -> Money.of(text, "PEN"))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("amount"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pen", "PE", "PENN", "ZZZ", "", "P1N"})
  @NullSource
  void should_reject_currency_when_it_is_not_a_valid_iso_4217_code(String currency) {
    assertThatThrownBy(() -> Money.of("10.00", currency))
        .isInstanceOfSatisfying(
            InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("currency"));
  }

  @Test
  void should_be_equal_when_amounts_have_same_value_with_different_scale() {
    assertThat(Money.of("10", "PEN")).isEqualTo(Money.of("10.00", "PEN"));
  }

  @Test
  void should_not_be_equal_when_currencies_differ() {
    assertThat(Money.of("10.00", "PEN")).isNotEqualTo(Money.of("10.00", "USD"));
  }
}
