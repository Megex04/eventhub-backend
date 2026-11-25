package com.eventhub.booking.application.port.in;

import java.time.Instant;
import java.util.UUID;

public record EventCancelledMessage(UUID messageId, UUID eventId, Instant occurredAt) {}
