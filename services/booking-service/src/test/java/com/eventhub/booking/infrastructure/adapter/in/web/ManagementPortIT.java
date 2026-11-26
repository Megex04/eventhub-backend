package com.eventhub.booking.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.support.SharedPostgres;
import com.eventhub.booking.support.SharedRabbit;
import com.eventhub.booking.support.TestConsumerSettings;
import com.eventhub.booking.support.TestOutboxSettings;
import com.eventhub.booking.support.TestSupportConfig;
import com.eventhub.booking.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Outside the local and test profiles the actuator lives on its own port, away from the public API,
 * and what is sensitive on it (prometheus) still needs a token. Here the management port is set
 * apart from the server's, as in a deployment.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0"})
@AutoConfigureMetrics
@ActiveProfiles("test")
@ContextConfiguration(
    initializers = {
      SharedPostgres.class,
      SharedRabbit.class,
      TestOutboxSettings.class,
      TestConsumerSettings.class
    })
@Import(TestSupportConfig.class)
class ManagementPortIT {

  @Value("${local.server.port}")
  private int serverPort;

  @Value("${local.management.port}")
  private int managementPort;

  private final HttpClient http = HttpClient.newHttpClient();

  private int get(int port, String path, String token) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  @Test
  void should_serve_the_actuator_on_a_port_other_than_the_api() {
    assertThat(managementPort).isNotEqualTo(serverPort);
  }

  @Test
  void should_expose_the_health_probes_without_a_token_on_the_management_port() throws Exception {
    assertThat(get(managementPort, "/actuator/health/liveness", null)).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/health/readiness", null)).isEqualTo(200);
  }

  @Test
  void should_protect_prometheus_with_a_token_on_the_management_port() throws Exception {
    assertThat(get(managementPort, "/actuator/prometheus", null)).isEqualTo(401);
    assertThat(get(managementPort, "/actuator/prometheus", TestTokens.forUser(UUID.randomUUID())))
        .isEqualTo(200);
  }

  @Test
  void should_not_serve_the_actuator_on_the_public_api_port() throws Exception {
    assertThat(get(serverPort, "/actuator/health/liveness", null)).isNotEqualTo(200);
    assertThat(get(serverPort, "/actuator/prometheus", null)).isNotEqualTo(200);
  }

  @Test
  void should_keep_the_api_on_the_server_port() throws Exception {
    assertThat(get(serverPort, "/api/v1/bookings", null)).isEqualTo(401);
  }
}
