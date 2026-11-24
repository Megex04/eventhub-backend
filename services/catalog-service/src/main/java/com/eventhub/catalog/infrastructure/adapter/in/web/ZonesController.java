package com.eventhub.catalog.infrastructure.adapter.in.web;

import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.RemoveZoneUseCase;
import com.eventhub.catalog.application.port.in.UpdateZoneUseCase;
import com.eventhub.catalog.infrastructure.adapter.in.web.api.ZonesApi;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.TicketZoneDto;
import com.eventhub.catalog.infrastructure.adapter.in.web.model.ZoneRequestDto;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ZonesController implements ZonesApi {

  private final AddZoneUseCase addZone;
  private final UpdateZoneUseCase updateZone;
  private final RemoveZoneUseCase removeZone;

  ZonesController(
      AddZoneUseCase addZone, UpdateZoneUseCase updateZone, RemoveZoneUseCase removeZone) {
    this.addZone = addZone;
    this.updateZone = updateZone;
    this.removeZone = removeZone;
  }

  @Override
  public ResponseEntity<TicketZoneDto> addZone(UUID eventId, ZoneRequestDto body) {
    var zone =
        addZone.addZone(EventWebMapper.toAddZoneCommand(eventId, CurrentUser.requireId(), body));
    return ResponseEntity.status(HttpStatus.CREATED).body(EventWebMapper.toDto(zone));
  }

  @Override
  public ResponseEntity<TicketZoneDto> updateZone(UUID eventId, UUID zoneId, ZoneRequestDto body) {
    var zone =
        updateZone.updateZone(
            EventWebMapper.toUpdateZoneCommand(eventId, zoneId, CurrentUser.requireId(), body));
    return ResponseEntity.ok(EventWebMapper.toDto(zone));
  }

  @Override
  public ResponseEntity<Void> removeZone(UUID eventId, UUID zoneId) {
    removeZone.removeZone(eventId, zoneId, CurrentUser.requireId());
    return ResponseEntity.noContent().build();
  }
}
