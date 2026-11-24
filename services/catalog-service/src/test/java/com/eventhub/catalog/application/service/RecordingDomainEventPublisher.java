package com.eventhub.catalog.application.service;

import com.eventhub.catalog.application.port.out.DomainEventPublisher;
import com.eventhub.catalog.domain.DomainEvent;
import java.util.ArrayList;
import java.util.List;

final class RecordingDomainEventPublisher implements DomainEventPublisher {

  private final List<DomainEvent> published = new ArrayList<>();

  @Override
  public void publish(List<DomainEvent> events) {
    published.addAll(events);
  }

  List<DomainEvent> published() {
    return List.copyOf(published);
  }
}
