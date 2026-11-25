package com.eventhub.catalog.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Operability: metrics are scrapeable but not public, and a failing request leaves a structured
 * JSON log line that carries the same {@code traceId} the client gets in the problem body and the
 * {@code correlationId} it sent. Boot tests turn metrics and tracing off unless asked.
 */
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends WebIntegrationTest {

  @Test
  void should_refuse_the_metrics_endpoint_when_there_is_no_token() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void should_serve_prometheus_metrics_including_the_outbox_when_the_caller_is_authenticated()
      throws Exception {
    String scrape =
        mockMvc
            .perform(withToken(get("/actuator/prometheus"), attendee()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Whole metric names: a counter called outbox_parked_total once hid the outbox_parked gauge.
    assertThat(scrape)
        .containsPattern("(?m)^outbox_pending[ {]")
        .containsPattern("(?m)^outbox_parked[ {]")
        .containsPattern("(?m)^outbox_oldest_age_seconds[ {]")
        .containsPattern("(?m)^outbox_published_total[ {]")
        .containsPattern("(?m)^outbox_failures_total[ {]")
        .containsPattern("(?m)^outbox_parkings_total[ {]")
        .contains("jvm_memory_used_bytes");
  }

  @Test
  void should_log_json_with_the_trace_and_correlation_ids_when_a_request_fails(
      CapturedOutput output) throws Exception {
    UUID correlationId = UUID.randomUUID();
    repositoryHook.onAfterLoad(id -> {
      throw new IllegalStateException("simulated failure inside the use case");
    });

    String body =
        mockMvc
            .perform(
                get(EVENTS + "/" + UUID.randomUUID())
                    .header("X-Correlation-Id", correlationId.toString()))
            .andExpect(status().isInternalServerError())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String traceId = JsonPath.read(body, "$.traceId");
    assertThat(traceId).matches("[0-9a-f]{32}");
    String logLine =
        output
            .getOut()
            .lines()
            .filter(line -> line.startsWith("{") && line.contains("Unhandled failure"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No JSON log line for the failure"));
    assertThat((String) JsonPath.read(logLine, "$.level")).isEqualTo("ERROR");
    assertThat((String) JsonPath.read(logLine, "$.correlationId"))
        .isEqualTo(correlationId.toString());
    assertThat((String) JsonPath.read(logLine, "$.traceId")).isEqualTo(traceId);
    assertThat(body).as("no stack trace or internals reach the client").doesNotContain("simulated");
  }
}
