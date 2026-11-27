package com.eventhub.booking.application.port.in;

import java.util.UUID;

public record PayBookingCommand(UUID userId, UUID bookingId, String paymentToken) {}
