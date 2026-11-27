package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.infrastructure.adapter.in.web.ApiIT;
import com.eventhub.booking.support.ControllableOutboxBroker;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Real PostgreSQL and real RabbitMQ. A test queue is bound to the exchange the way a consumer would
 * bind its own (the service declares none), and the relay is driven by calling {@code
 * publishPending()} so every step is deterministic.
 */
public abstract class MessagingIT extends ApiIT {

  protected static final String EXCHANGE = "eventhub.events";
  protected static final String QUEUE = "booking.it.events";

  @Autowired protected OutboxRelay relay;
  @Autowired protected AmqpAdmin admin;
  @Autowired protected RabbitTemplate rabbit;
  @Autowired protected MeterRegistry meters;
  @Autowired protected ControllableOutboxBroker broker;
  @Autowired protected ReconcileRefundsUseCase reconcile;

  @BeforeEach
  void bindTestQueue() {
    TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
    Queue queue = new Queue(QUEUE, true, false, false);
    admin.declareExchange(exchange);
    admin.declareQueue(queue);
    admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("booking.booking.*.v1"));
    admin.purgeQueue(QUEUE);
  }

  /** Creates through the API, sending the given correlation id like a caller would. */
  protected ResultActions createWithCorrelation(
      String token, UUID key, String body, UUID correlationId) throws Exception {
    return mvc.perform(
        post(BOOKINGS)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key.toString())
            .header("X-Correlation-Id", correlationId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** One second between steps, as real requests are apart. */
  protected void tick() {
    clock.advance(Duration.ofSeconds(1));
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

  /** Everything that arrives, until the queue stays quiet for a moment. */
  protected List<Message> receiveAll() {
    List<Message> messages = new ArrayList<>();
    for (Message message = receive(); message != null; message = rabbit.receive(QUEUE, 1500)) {
      messages.add(message);
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

  protected static List<String> keysOf(List<Message> messages) {
    return messages.stream().map(MessagingIT::routingKey).toList();
  }

  protected UUID onlyOutboxRowOf(UUID bookingId) {
    return app.jdbc.queryForObject(
        "SELECT id FROM outbox_event WHERE aggregate_id = ?", UUID.class, bookingId);
  }

  protected UUID outboxRowOf(UUID bookingId, String eventType) {
    return app.jdbc.queryForObject(
        "SELECT id FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
        UUID.class,
        bookingId,
        eventType);
  }

  protected Map<String, Object> row(UUID rowId) {
    return app.jdbc.queryForMap(
        "SELECT published_at, attempts, last_error, next_attempt_at FROM outbox_event WHERE id = ?",
        rowId);
  }

  protected double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  protected double count(String name) {
    return meters.get(name).counter().count();
  }
}
