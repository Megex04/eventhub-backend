package com.eventhub.catalog.application.query;

import com.eventhub.catalog.domain.EventStatus;
import java.time.Instant;
import java.util.UUID;

/** Read model for listings; carries no zones so the query never needs a join per event. */
public record EventSummary(
    UUID id, String title, String venue, Instant startsAt, Instant endsAt, EventStatus status) {}
