package com.eventhub.catalog.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcCorrelationIdProviderTest {

  private final MdcCorrelationIdProvider provider = new MdcCorrelationIdProvider();

  @AfterEach
  void clearContext() {
    MDC.remove(MdcCorrelationIdProvider.MDC_KEY);
  }

  @Test
  void should_return_the_id_when_the_logging_context_holds_a_uuid() {
    UUID id = UUID.randomUUID();
    MDC.put(MdcCorrelationIdProvider.MDC_KEY, id.toString());

    assertThat(provider.current()).contains(id);
  }

  @Test
  void should_return_empty_when_the_logging_context_has_no_id() {
    assertThat(provider.current()).isEmpty();
  }

  @Test
  void should_return_empty_when_the_stored_value_is_not_a_uuid() {
    MDC.put(MdcCorrelationIdProvider.MDC_KEY, "not-a-uuid");

    assertThat(provider.current()).isEmpty();
  }
}
