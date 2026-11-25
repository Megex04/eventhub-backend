package com.eventhub.booking.application.port.out;

import java.util.UUID;

/** The Idempotency-Key of a creation request and the fingerprint of its normalized body. */
public record IdempotencyRecord(UUID userId, UUID key, String fingerprint) {}
