package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.infrastructure.config.MdcCorrelationIdProvider;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private static final UUID GENERATED = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  private final CorrelationIdFilter filter = new CorrelationIdFilter(() -> GENERATED);
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("GET", "/api/v1/events");
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final AtomicReference<String> seenInsideChain = new AtomicReference<>();
  private final FilterChain chain =
      (req, res) -> seenInsideChain.set(MDC.get(MdcCorrelationIdProvider.MDC_KEY));

  @AfterEach
  void clearContext() {
    MDC.clear();
  }

  @Test
  void should_use_the_caller_id_when_the_header_is_a_valid_uuid() throws Exception {
    UUID sent = UUID.randomUUID();
    request.addHeader(CorrelationIdFilter.HEADER, sent.toString());

    filter.doFilter(request, response, chain);

    assertThat(seenInsideChain.get()).isEqualTo(sent.toString());
    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(sent.toString());
  }

  @Test
  void should_generate_an_id_when_the_header_is_missing() throws Exception {
    filter.doFilter(request, response, chain);

    assertThat(seenInsideChain.get()).isEqualTo(GENERATED.toString());
    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(GENERATED.toString());
  }

  @Test
  void should_replace_the_header_with_a_generated_id_when_it_is_not_a_uuid() throws Exception {
    request.addHeader(CorrelationIdFilter.HEADER, "not-a-uuid; drop table");

    filter.doFilter(request, response, chain);

    assertThat(seenInsideChain.get()).isEqualTo(GENERATED.toString());
    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(GENERATED.toString());
  }

  @Test
  void should_clear_the_logging_context_when_the_request_ends() throws Exception {
    filter.doFilter(request, response, chain);

    assertThat(MDC.get(MdcCorrelationIdProvider.MDC_KEY)).isNull();
  }

  @Test
  void should_clear_the_logging_context_when_the_chain_fails() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("boom");
        };

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> filter.doFilter(request, response, failing))
        .isInstanceOf(IllegalStateException.class);

    assertThat(MDC.get(MdcCorrelationIdProvider.MDC_KEY)).isNull();
  }
}
