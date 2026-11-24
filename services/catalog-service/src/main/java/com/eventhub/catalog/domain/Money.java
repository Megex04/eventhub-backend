package com.eventhub.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Non-negative amount with scale 2 (HALF_EVEN) in an ISO 4217 currency. Never a float/double. */
public record Money(BigDecimal amount, String currency) {

  private static final int SCALE = 2;
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

  public Money {
    Validation.required("amount", amount);
    amount = amount.setScale(SCALE, RoundingMode.HALF_EVEN);
    if (amount.signum() < 0) {
      throw new InvalidValueException("amount", "amount must not be negative");
    }
    if (amount.compareTo(MAX_AMOUNT) > 0) {
      throw new InvalidValueException("amount", "amount must have at most 10 integer digits");
    }
    requireIsoCurrency(currency);
  }

  public static Money of(String amount, String currency) {
    return new Money(parse(amount), currency);
  }

  private static BigDecimal parse(String amount) {
    Validation.required("amount", amount);
    try {
      return new BigDecimal(amount);
    } catch (NumberFormatException e) {
      throw new InvalidValueException("amount", "amount must be a decimal number");
    }
  }

  private static void requireIsoCurrency(String currency) {
    if (currency == null || !currency.matches("[A-Z]{3}") || !isKnownCurrency(currency)) {
      throw new InvalidValueException("currency", "currency must be a valid ISO 4217 code");
    }
  }

  private static boolean isKnownCurrency(String code) {
    try {
      Currency.getInstance(code);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
