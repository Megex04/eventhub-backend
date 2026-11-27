package com.eventhub.booking.application.port.in;

public interface ApplyEventPublishedUseCase {

  void apply(EventPublishedMessage message);
}
