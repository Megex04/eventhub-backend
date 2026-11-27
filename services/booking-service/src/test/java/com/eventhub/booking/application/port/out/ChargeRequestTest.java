package com.eventhub.booking.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChargeRequestTest {

  @Test
  void should_never_print_the_token_when_converted_to_text() {
    ChargeRequest request =
        new ChargeRequest(UUID.randomUUID(), Money.of("10.00", "PEN"), "tok_secret_value");

    assertThat(request.toString()).doesNotContain("tok_secret_value").contains("***");
  }
}
