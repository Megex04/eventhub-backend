package com.eventhub.catalog.infrastructure.adapter.in.web;

import com.eventhub.catalog.application.port.in.CancelEventUseCase;
import com.eventhub.catalog.application.port.in.PublishEventUseCase;
import com.eventhub.catalog.application.port.in.RescheduleEventUseCase;
import com.eventhub.catalog.infrastructure.adapter.in.web.api.LifecycleApi;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.CancelEventRequestDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.EventDetailDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.RescheduleEventRequestDto;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LifecycleController implements LifecycleApi {

  private final PublishEventUseCase publishEvent;
  private final CancelEventUseCase cancelEvent;
  private final RescheduleEventUseCase rescheduleEvent;

  LifecycleController(
      PublishEventUseCase publishEvent,
      CancelEventUseCase cancelEvent,
      RescheduleEventUseCase rescheduleEvent) {
    this.publishEvent = publishEvent;
    this.cancelEvent = cancelEvent;
    this.rescheduleEvent = rescheduleEvent;
  }

  @Override
  public ResponseEntity<EventDetailDto> publishEvent(UUID eventId) {
    var event = publishEvent.publish(eventId, CurrentUser.requireId());
    return ResponseEntity.ok(EventWebMapper.toDto(event));
  }

  @Override
  public ResponseEntity<EventDetailDto> cancelEvent(UUID eventId, CancelEventRequestDto body) {
    var event =
        cancelEvent.cancel(EventWebMapper.toCommand(eventId, CurrentUser.requireId(), body));
    return ResponseEntity.ok(EventWebMapper.toDto(event));
  }

  @Override
  public ResponseEntity<EventDetailDto> rescheduleEvent(
      UUID eventId, RescheduleEventRequestDto body) {
    var event =
        rescheduleEvent.reschedule(
            EventWebMapper.toCommand(eventId, CurrentUser.requireId(), body));
    return ResponseEntity.ok(EventWebMapper.toDto(event));
  }
}
