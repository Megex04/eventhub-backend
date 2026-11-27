package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.ProjectedZone;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventPublishedMessage(
    UUID messageId,
    UUID eventId,
    Instant startsAt,
    Instant endsAt,
    List<ProjectedZone> zones,
    Instant occurredAt) {}
