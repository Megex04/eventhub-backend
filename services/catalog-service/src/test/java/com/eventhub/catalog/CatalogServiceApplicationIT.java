package com.eventhub.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.catalog.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class CatalogServiceApplicationIT extends PostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void should_report_liveness_up_when_application_starts() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void should_report_readiness_up_when_application_starts() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void should_apply_the_flyway_migration_when_the_context_starts(
      @Autowired JdbcTemplate jdbcTemplate) {
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
            Integer.class);

    org.assertj.core.api.Assertions.assertThat(applied).isEqualTo(1);
  }
}
