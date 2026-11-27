package com.eventhub.booking.application.port.in;

import java.time.Instant;
import java.util.UUID;

public record EventRescheduledMessage(
    UUID messageId, UUID eventId, Instant startsAt, Instant endsAt, Instant occurredAt) {}
