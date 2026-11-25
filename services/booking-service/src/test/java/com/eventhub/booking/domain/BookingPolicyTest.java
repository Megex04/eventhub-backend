package com.eventhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BookingPolicyTest {

  @Test
  void should_expose_the_configured_hold_and_ticket_limit() {
    BookingPolicy policy = new BookingPolicy(Duration.ofMinutes(10), 6);

    assertThat(policy.holdDuration()).isEqualTo(Duration.ofMinutes(10));
    assertThat(policy.maxTickets()).isEqualTo(6);
  }

  @Test
  void should_reject_a_zero_hold_duration() {
    assertThatThrownBy(() -> new BookingPolicy(Duration.ZERO, 6))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "holdDuration");
  }

  @Test
  void should_reject_a_negative_hold_duration() {
    assertThatThrownBy(() -> new BookingPolicy(Duration.ofMinutes(-1), 6))
        .isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_a_missing_hold_duration() {
    assertThatThrownBy(() -> new BookingPolicy(null, 6)).isInstanceOf(InvalidValueException.class);
  }

  @Test
  void should_reject_a_ticket_limit_below_one() {
    assertThatThrownBy(() -> new BookingPolicy(Duration.ofMinutes(10), 0))
        .isInstanceOf(InvalidValueException.class)
        .hasFieldOrPropertyWithValue("field", "maxTickets");
  }
}
