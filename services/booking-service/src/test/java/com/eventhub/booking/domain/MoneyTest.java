package com.eventhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.BusinessRuleViolationException.Rule;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  @ParameterizedTest
  @CsvSource({"10,10.00", "10.1,10.10", "0.125,0.12", "0.135,0.14", "0.145,0.14", "99.999,100.00"})
  void should_round_half_even_to_two_decimals_when_amount_has_other_scale(
      String raw, String expected) {
    assertThat(Money.of(raw, "PEN").amount()).isEqualTo(new BigDecimal(expected));
  }

  @Test
  void should_be_equal_when_amounts_differ_only_in_scale() {
    assertThat(Money.of("10", "PEN")).isEqualTo(Money.of("10.00", "PEN"));
  }

  @Test
  void should_reject_negative_amounts() {
    assertThatThrownBy(() -> Money.of("-0.01", "PEN"))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "amount");
  }

  @Test
  void should_reject_amounts_with_more_than_ten_integer_digits() {
    assertThatThrownBy(() -> Money.of("10000000000.00", "PEN"))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_a_non_numeric_amount() {
    assertThatThrownBy(() -> Money.of("abc", "PEN")).isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_a_missing_amount() {
    assertThatThrownBy(() -> new Money(null, "PEN")).isInstanceOf(InvalidValueException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pen", "PE", "PENN", "XXQ", ""})
  void should_reject_invalid_currencies(String currency) {
    assertThatThrownBy(() -> Money.of("1.00", currency))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "currency");
  }

  @Test
  void should_reject_a_missing_currency() {
    assertThatThrownBy(() -> Money.of("1.00", null)).isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_add_amounts_when_currencies_match() {
    assertThat(Money.of("10.50", "PEN").plus(Money.of("0.75", "PEN")))
        .isEqualTo(Money.of("11.25", "PEN"));
  }

  @Test
  void should_report_mixed_currency_when_adding_different_currencies() {
    assertThatThrownBy(() -> Money.of("1.00", "PEN").plus(Money.of("1.00", "USD")))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.MIXED_CURRENCY));
  }

  @Test
  void should_multiply_by_a_quantity() {
    assertThat(Money.of("33.33", "PEN").times(3)).isEqualTo(Money.of("99.99", "PEN"));
  }

  @Test
  void should_reject_a_quantity_below_one_when_multiplying() {
    assertThatThrownBy(() -> Money.of("1.00", "PEN").times(0))
        .isInstanceOf(InvalidValueException.class);
  }
}
