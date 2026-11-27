package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.PersistenceFailureException;
import com.eventhub.booking.domain.BookingPaymentRefunded;
import com.eventhub.booking.domain.DomainEvent;
import java.util.List;

/**
 * Publishes everything except the refund event, whose write "fails" like a database that cannot
 * record the refund after the gateway already gave the money back.
 */
final class FailingRefundEventsPublisher implements DomainEventPublisher {

  private final DomainEventPublisher delegate;

  FailingRefundEventsPublisher(DomainEventPublisher delegate) {
    this.delegate = delegate;
  }

  @Override
  public void publish(List<DomainEvent> events) {
    if (events.stream().anyMatch(BookingPaymentRefunded.class::isInstance)) {
      throw new PersistenceFailureException("simulated: the refund cannot be recorded", null);
    }
    delegate.publish(events);
  }
}
