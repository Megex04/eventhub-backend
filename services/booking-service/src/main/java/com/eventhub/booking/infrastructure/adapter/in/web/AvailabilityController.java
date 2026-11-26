package com.eventhub.booking.infrastructure.adapter.in.web;

import com.eventhub.booking.application.port.in.GetAvailabilityUseCase;
import com.eventhub.booking.infrastructure.adapter.in.web.api.AvailabilityApi;
import com.eventhub.booking.infrastructure.adapter.in.web.model.EventAvailabilityDto;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Public read of how many tickets are left, from the local projection. */
@RestController
class AvailabilityController implements AvailabilityApi {

  private final GetAvailabilityUseCase getAvailability;

  AvailabilityController(GetAvailabilityUseCase getAvailability) {
    this.getAvailability = getAvailability;
  }

  @Override
  public ResponseEntity<EventAvailabilityDto> getEventAvailability(UUID eventId) {
    return ResponseEntity.ok(BookingWebMapper.toAvailability(getAvailability.get(eventId)));
  }
}
