package com.eventhub.booking.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconcilerPolicyTest {

  private static final ReconcilerPolicy POLICY =
      new ReconcilerPolicy(
          Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMinutes(2), 10);

  @Test
  void should_wait_the_initial_backoff_after_the_first_failure() {
    assertThat(POLICY.backoffAfter(0)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void should_double_the_wait_with_every_failure() {
    assertThat(POLICY.backoffAfter(1)).isEqualTo(Duration.ofSeconds(60));
    assertThat(POLICY.backoffAfter(2)).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void should_never_wait_longer_than_the_cap_when_there_are_many_failures() {
    assertThat(POLICY.backoffAfter(3)).isEqualTo(Duration.ofMinutes(2));
    assertThat(POLICY.backoffAfter(50)).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void should_reject_a_cap_below_the_initial_backoff() {
    assertThatThrownBy(() ->
            new ReconcilerPolicy(
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(1), 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_reject_a_batch_below_one() {
    assertThatThrownBy(() ->
            new ReconcilerPolicy(
                Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(2), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
