package com.eventhub.contracttesting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenApiContractsTest {

  @Test
  void should_load_a_contract_by_file_name_when_it_exists() {
    assertThat(OpenApiContracts.validatorFor("booking-api.yaml")).isNotNull();
    assertThat(OpenApiContracts.validatorFor("catalog-api.yaml")).isNotNull();
  }

  @Test
  void should_fail_when_the_contract_does_not_exist() {
    assertThatThrownBy(() -> OpenApiContracts.validatorFor("missing-api.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing-api.yaml");
  }
}
