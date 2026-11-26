package com.eventhub.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.support.PersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@PersistenceIT
@AutoConfigureMockMvc
class BookingServiceApplicationIT {

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
  void should_reject_a_booking_request_when_there_is_no_token() throws Exception {
    mockMvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
  }
}
