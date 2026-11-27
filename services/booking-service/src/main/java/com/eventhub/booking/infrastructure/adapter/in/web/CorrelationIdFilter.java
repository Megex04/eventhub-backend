package com.eventhub.booking.infrastructure.adapter.in.web;

import com.eventhub.booking.application.port.out.IdGenerator;
import com.eventhub.booking.infrastructure.config.MdcCorrelationIdProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id: the one the caller sent in {@code X-Correlation-Id} when it
 * is a valid UUID, a fresh one otherwise. It goes to the logging context and back to the caller in
 * the response. It runs before the security chain, so even a rejected request is traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Correlation-Id";

  private final IdGenerator ids;

  CorrelationIdFilter(IdGenerator ids) {
    this.ids = ids;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    UUID correlationId = fromHeader(request.getHeader(HEADER)).orElseGet(ids::newId);
    MDC.put(MdcCorrelationIdProvider.MDC_KEY, correlationId.toString());
    response.setHeader(HEADER, correlationId.toString());
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MdcCorrelationIdProvider.MDC_KEY);
    }
  }

  private static Optional<UUID> fromHeader(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value.strip()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
