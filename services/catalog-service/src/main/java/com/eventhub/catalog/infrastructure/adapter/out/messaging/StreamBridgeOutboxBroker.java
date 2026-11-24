package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * Sends through Spring Cloud Stream and waits for the broker's publisher confirm, so a row is only
 * ever marked published after RabbitMQ accepted it. {@code StreamBridge.send} alone returns before
 * that: the confirm arrives later on the {@link CorrelationData} carried in a header.
 *
 * <p>A confirm means the broker took the message and routed it; if no queue is bound to the routing
 * key RabbitMQ still confirms and drops it. That is how a topic exchange behaves and not a failure
 * of the catalog: consumers declare their queues, and events published before that are not
 * replayed.
 */
@Component
class StreamBridgeOutboxBroker implements OutboxBroker {

  /**
   * Binding configured in application.yml: exchange {@code eventhub.events}, topic, per-message
   * key.
   */
  static final String BINDING = "outbox-out-0";

  /** First segment of every routing key this service produces. */
  static final String SERVICE = "catalog";

  private final StreamBridge bridge;
  private final OutboxProperties properties;

  StreamBridgeOutboxBroker(StreamBridge bridge, OutboxProperties properties) {
    this.bridge = bridge;
    this.properties = properties;
  }

  @Override
  public void publish(OutboxMessage message) {
    CorrelationData confirmation = new CorrelationData(message.id().toString());
    try {
      if (!bridge.send(BINDING, toMessage(message, confirmation))) {
        throw new BrokerPublishException("The binder did not accept the message " + message.id());
      }
      awaitConfirmation(message, confirmation);
    } catch (MessagingException | AmqpException e) {
      throw new BrokerPublishException("Sending " + message.id() + " failed: " + e.getMessage(), e);
    }
  }

  private Message<byte[]> toMessage(OutboxMessage message, CorrelationData confirmation) {
    String routingKey =
        RoutingKeys.of(
            SERVICE, message.aggregateType(), message.eventType(), message.eventVersion());
    return MessageBuilder.withPayload(message.payload().getBytes(StandardCharsets.UTF_8))
        .setHeader("routingKey", routingKey)
        .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, confirmation)
        .setHeader(AmqpHeaders.MESSAGE_ID, message.id().toString())
        .setHeader(AmqpHeaders.CORRELATION_ID, message.correlationId().toString())
        .setHeader("contentType", MimeTypeUtils.APPLICATION_JSON_VALUE)
        .build();
  }

  private void awaitConfirmation(OutboxMessage message, CorrelationData confirmation) {
    try {
      CorrelationData.Confirm confirm =
          confirmation
              .getFuture()
              .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) {
        throw new BrokerPublishException(
            "The broker rejected %s: %s".formatted(message.id(), confirm.reason()));
      }
    } catch (TimeoutException e) {
      throw new BrokerPublishException(
          "No confirmation for %s within %s".formatted(message.id(), properties.confirmTimeout()),
          e);
    } catch (ExecutionException e) {
      throw new BrokerPublishException("Confirmation of " + message.id() + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BrokerPublishException("Interrupted while confirming " + message.id(), e);
    }
  }
}
