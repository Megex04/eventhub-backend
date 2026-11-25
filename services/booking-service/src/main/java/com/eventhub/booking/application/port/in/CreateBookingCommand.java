package com.eventhub.booking.application.port.in;

import com.eventhub.booking.domain.RequestedItem;
import java.util.List;
import java.util.UUID;

public record CreateBookingCommand(
    UUID userId, UUID idempotencyKey, UUID eventId, List<RequestedItem> items) {}
