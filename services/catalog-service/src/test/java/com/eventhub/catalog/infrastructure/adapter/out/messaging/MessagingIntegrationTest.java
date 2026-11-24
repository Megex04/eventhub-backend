package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.catalog.infrastructure.adapter.in.web.WebIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real PostgreSQL and real RabbitMQ. A test queue is bound to the exchange the way a consumer would
 * bind its own (the service declares none), and the relay is driven by calling {@code
 * publishPending()} so every step is deterministic.
 */
abstract class MessagingIntegrationTest extends WebIntegrationTest {

  protected static final String EXCHANGE = "eventhub.events";
  protected static final String QUEUE = "catalog.it.events";

  @Autowired protected OutboxRelay relay;
  @Autowired protected AmqpAdmin admin;
  @Autowired protected RabbitTemplate rabbit;
  @Autowired protected MeterRegistry meters;

  @BeforeEach
  void bindTestQueue() {
    TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
    Queue queue = new Queue(QUEUE, true, false, false);
    admin.declareExchange(exchange);
    admin.declareQueue(queue);
    admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("catalog.event.*.v1"));
    admin.purgeQueue(QUEUE);
  }

  /** Publishes through the API, sending the given correlation id like a caller would. */
  protected void publishViaApi(UUID eventId, UUID correlationId) throws Exception {
    mockMvc
        .perform(
            withToken(post(EVENTS + "/" + eventId + "/publish"), organizer())
                .header("X-Correlation-Id", correlationId.toString()))
        .andExpect(status().isOk());
  }

  protected void publishViaApi(UUID eventId) throws Exception {
    publishViaApi(eventId, UUID.randomUUID());
  }

  protected void rescheduleViaApi(UUID eventId) throws Exception {
    Instant start = inDays(45);
    mockMvc
        .perform(
            json(
                withToken(post(EVENTS + "/" + eventId + "/reschedule"), organizer()),
                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                    .formatted(start, start.plus(Duration.ofHours(3)))))
        .andExpect(status().isOk());
  }

  protected void cancelViaApi(UUID eventId) throws Exception {
    mockMvc
        .perform(
            json(
                withToken(post(EVENTS + "/" + eventId + "/cancel"), organizer()),
                "{\"reason\":\"Venue unavailable\"}"))
        .andExpect(status().isOk());
  }

  /** Runs relay passes until one sends nothing; returns how many rows were sent in total. */
  protected int drain() {
    int total = 0;
    for (int pass = 0; pass < 50; pass++) {
      int sent = relay.publishPending();
      if (sent == 0) {
        return total;
      }
      total += sent;
    }
    throw new IllegalStateException("The relay never ran dry");
  }

  /** Waits up to five seconds for the next message of the test queue. */
  protected Message receive() {
    Message message = rabbit.receive(QUEUE, 5000);
    if (message == null) {
      throw new AssertionError("No message arrived in " + QUEUE);
    }
    return message;
  }

  protected List<Message> receive(int count) {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      messages.add(receive());
    }
    return messages;
  }

  protected boolean queueIsEmpty() {
    return rabbit.receive(QUEUE, 700) == null;
  }

  protected static String body(Message message) {
    return new String(message.getBody(), StandardCharsets.UTF_8);
  }

  protected static String field(Message message, String jsonPath) {
    return JsonPath.read(body(message), jsonPath);
  }

  protected static String routingKey(Message message) {
    return message.getMessageProperties().getReceivedRoutingKey();
  }

  protected UUID onlyOutboxRowOf(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT id FROM outbox_event WHERE aggregate_id = ?", UUID.class, eventId);
  }

  protected double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  protected double count(String name) {
    return meters.get(name).counter().count();
  }
}
