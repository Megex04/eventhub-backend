package com.eventhub.booking.application.service;

import com.eventhub.booking.application.exception.EventProjectionMissingException;
import com.eventhub.booking.application.port.in.ApplyEventRescheduledUseCase;
import com.eventhub.booking.application.port.in.EventRescheduledMessage;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.EventProjection.ApplyResult;

/**
 * Applies {@code EventRescheduled}. A reschedule for an event never seen here is not discarded: it
 * fails, which rolls back the "processed" mark made in the same transaction, so the broker
 * redelivers it with backoff (and dead-letters it after the configured attempts) until the
 * publication arrives.
 */
public class ApplyEventRescheduledService implements ApplyEventRescheduledUseCase {

  private final TransactionRunner transactions;
  private final EventProjectionRepository projections;
  private final ProcessedMessageRepository processed;

  public ApplyEventRescheduledService(
      TransactionRunner transactions,
      EventProjectionRepository projections,
      ProcessedMessageRepository processed) {
    this.transactions = transactions;
    this.projections = projections;
    this.processed = processed;
  }

  @Override
  public void apply(EventRescheduledMessage message) {
    transactions.run(() -> applyOnce(message));
  }

  private void applyOnce(EventRescheduledMessage message) {
    if (!processed.markProcessed(message.messageId())) {
      return;
    }
    EventProjection projection =
        projections
            .findById(message.eventId())
            .orElseThrow(() -> new EventProjectionMissingException(message.eventId()));
    ApplyResult result =
        projection.applyRescheduled(message.startsAt(), message.endsAt(), message.occurredAt());
    if (result == ApplyResult.APPLIED) {
      projections.save(projection);
    }
  }
}
