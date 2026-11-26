package com.eventhub.booking.infrastructure.adapter.in.messaging;

import com.eventhub.booking.application.port.in.ApplyEventCancelledUseCase;
import com.eventhub.booking.application.port.in.ApplyEventPublishedUseCase;
import com.eventhub.booking.application.port.in.ApplyEventRescheduledUseCase;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single consumer of the catalog events, bound by Spring Cloud Stream to one queue that carries
 * the three routing keys, with concurrency 1 (see ADR 0008). A failure is rethrown so the binder
 * retries with backoff and, when attempts run out, republishes to the dead-letter queue; a poison
 * message skips the retries. It reads the message, applies it through its use case and counts it.
 * It logs only the type and the id of a message, never its content.
 */
@Component
class CatalogEventsConsumer {

  private static final Logger log = LoggerFactory.getLogger(CatalogEventsConsumer.class);

  /** Projection writes of two messages can collide; the loser reloads and applies again. */
  private static final int CONFLICT_ATTEMPTS = 3;

  private final CatalogEventReader reader;
  private final ApplyEventPublishedUseCase published;
  private final ApplyEventRescheduledUseCase rescheduled;
  private final ApplyEventCancelledUseCase cancelled;
  private final CatalogEventsMetrics metrics;
  private final int maxAttempts;

  CatalogEventsConsumer(
      JsonMapper json,
      ApplyEventPublishedUseCase published,
      ApplyEventRescheduledUseCase rescheduled,
      ApplyEventCancelledUseCase cancelled,
      CatalogEventsMetrics metrics,
      @Value("${spring.cloud.stream.bindings.catalogEvents-in-0.consumer.max-attempts:3}")
          int maxAttempts) {
    this.reader = new CatalogEventReader(json);
    this.published = published;
    this.rescheduled = rescheduled;
    this.cancelled = cancelled;
    this.metrics = metrics;
    this.maxAttempts = maxAttempts;
  }

  /** The functional binding {@code catalogEvents-in-0}. */
  @Bean
  Consumer<Message<byte[]>> catalogEvents() {
    return message -> handle(message.getPayload(), deliveryAttempt(message));
  }

  /** The binder's retry counts attempts in an {@code AtomicInteger} header. */
  private static int deliveryAttempt(Message<?> message) {
    Object attempt = message.getHeaders().get(IntegrationMessageHeaderAccessor.DELIVERY_ATTEMPT);
    return attempt instanceof AtomicInteger count ? count.get() : 1;
  }

  void handle(byte[] body, int attempt) {
    String type = CatalogEventsMetrics.UNREADABLE;
    boolean applied = false;
    boolean poison = false;
    try {
      CatalogEventReader.Parsed event = reader.read(body);
      type = event.type();
      applyWithRetryOnConflict(event);
      applied = true;
      metrics.consumed(type);
    } catch (PoisonMessageException e) {
      poison = true;
      throw e;
    } finally {
      if (!applied) {
        metrics.failed(type);
        log.warn("A catalog message of type {} could not be applied (attempt {})", type, attempt);
        // The binder dead-letters it right after a poison failure or the last attempt.
        if (poison || attempt >= maxAttempts) {
          metrics.deadLettered();
        }
      }
    }
  }

  private void applyWithRetryOnConflict(CatalogEventReader.Parsed event) {
    for (int attempt = 1; ; attempt++) {
      try {
        dispatch(event);
        return;
      } catch (ConcurrencyConflictException conflict) {
        if (attempt == CONFLICT_ATTEMPTS) {
          throw conflict;
        }
      }
    }
  }

  private void dispatch(CatalogEventReader.Parsed event) {
    switch (event) {
      case CatalogEventReader.Parsed.Published e -> published.apply(e.message());
      case CatalogEventReader.Parsed.Rescheduled e -> rescheduled.apply(e.message());
      case CatalogEventReader.Parsed.Cancelled e -> cancelled.apply(e.message());
    }
  }
}
