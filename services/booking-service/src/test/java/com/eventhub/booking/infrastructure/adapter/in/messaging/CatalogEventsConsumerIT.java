package com.eventhub.booking.infrastructure.adapter.in.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static com.eventhub.booking.support.CatalogPayloads.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.infrastructure.adapter.in.web.ApiIT;
import com.eventhub.booking.support.CatalogPayloads.Zone;
import com.eventhub.booking.support.SharedRabbit;
import com.eventhub.booking.support.TestConsumerSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binding.BindingsLifecycleController;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The consumer of catalog events on a real RabbitMQ and PostgreSQL: the topology it declares, the
 * order problems and duplicates a broker can produce, catalog's re-emission, the cascade of a
 * cancellation and the dead-letter queue. The binding is stopped between tests (see {@link
 * TestConsumerSettings}) so that this test is the only consumer of the queue.
 */
class CatalogEventsConsumerIT extends ApiIT {

  private static final String EXCHANGE = "eventhub.events";
  private static final String QUEUE = "booking-catalog-events";
  private static final String DLQ = "booking-catalog-events.dlq";
  private static final Duration PATIENCE = Duration.ofSeconds(20);
  private static final Instant T1 = NOW;
  private static final Instant T2 = NOW.plusSeconds(60);
  private static final Instant T3 = NOW.plusSeconds(120);
  private static final Instant ENDS = EVENT_STARTS.plus(Duration.ofHours(3));

  @Autowired private RabbitTemplate rabbit;
  @Autowired private AmqpAdmin admin;
  @Autowired private MeterRegistry meters;
  @Autowired private BindingsLifecycleController bindings;
  @Autowired private ReconcileRefundsUseCase reconcile;

  @BeforeEach
  void startTheConsumer() {
    admin.purgeQueue(QUEUE);
    admin.purgeQueue(DLQ);
    bindings.start(TestConsumerSettings.BINDING);
  }

  @AfterEach
  void stopTheConsumer() {
    bindings.stop(TestConsumerSettings.BINDING);
  }

  // ---------------------------------------------------------------- helpers

  private static List<Zone> zones() {
    return List.of(
        new Zone(uuid(3101), "VIP", 20, "150.00"), new Zone(uuid(3102), "General", 100, "50.00"));
  }

  private static Message message(String body) {
    return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
        .setContentType("application/json")
        .build();
  }

  private void send(String routingKey, String body) {
    rabbit.send(EXCHANGE, routingKey, message(body));
  }

  private static void eventually(BooleanSupplier condition) {
    await().atMost(PATIENCE).pollInterval(Duration.ofMillis(50)).until(condition::getAsBoolean);
  }

  private Optional<String> projectionStatus(UUID eventId) {
    return app
        .jdbc
        .queryForList(
            "SELECT status FROM event_projection WHERE event_id = ?", String.class, eventId)
        .stream()
        .findFirst();
  }

  private Optional<Instant> startsAt(UUID eventId) {
    return app
        .jdbc
        .queryForList(
            "SELECT starts_at FROM event_projection WHERE event_id = ?",
            java.time.OffsetDateTime.class,
            eventId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .map(java.time.OffsetDateTime::toInstant)
        .findFirst();
  }

  private Instant lastOccurredAt(UUID eventId) {
    return app.jdbc
        .queryForObject(
            "SELECT last_occurred_at FROM event_projection WHERE event_id = ?",
            java.time.OffsetDateTime.class,
            eventId)
        .toInstant();
  }

  private int zoneCount(UUID eventId) {
    return (int)
        app.count("SELECT count(*) FROM projected_zone WHERE event_id = '" + eventId + "'");
  }

  private int reservedOf(UUID eventId, UUID zoneId) {
    return app.jdbc.queryForObject(
        "SELECT reserved FROM zone_inventory WHERE event_id = ? AND zone_id = ?",
        Integer.class,
        eventId,
        zoneId);
  }

  private int capacityOf(UUID eventId, UUID zoneId) {
    return app.jdbc.queryForObject(
        "SELECT capacity FROM zone_inventory WHERE event_id = ? AND zone_id = ?",
        Integer.class,
        eventId,
        zoneId);
  }

  private long processedMessages() {
    return app.count("SELECT count(*) FROM processed_message");
  }

  private boolean wasProcessed(UUID messageId) {
    return app.count(
            "SELECT count(*) FROM processed_message WHERE message_id = '" + messageId + "'")
        == 1;
  }

  private double consumed(String type) {
    return meters.counter(CatalogEventsMetrics.CONSUMED, "type", type).count();
  }

  private double failed(String type) {
    return meters.counter(CatalogEventsMetrics.FAILED, "type", type).count();
  }

  private double deadLettered() {
    return meters.counter(CatalogEventsMetrics.DEAD_LETTERED).count();
  }

  private Message deadLetter() {
    Message dead = rabbit.receive(DLQ, 15_000);
    assertThat(dead).as("a message in the dead-letter queue").isNotNull();
    return dead;
  }

  private boolean deadLetterQueueIsEmpty() {
    return rabbit.receive(DLQ, 700) == null;
  }

  /** Sends a valid message for another event and waits for it: everything before it was handled. */
  private void awaitTheQueueToReachThisPoint() {
    UUID marker = UUID.randomUUID();
    send(PUBLISHED_KEY, published(UUID.randomUUID(), marker, T1, EVENT_STARTS, ENDS, zones()));
    eventually(() -> projectionStatus(marker).isPresent());
  }

  private void publish(UUID eventId, Instant occurredAt) {
    send(
        PUBLISHED_KEY,
        published(UUID.randomUUID(), eventId, occurredAt, EVENT_STARTS, ENDS, zones()));
  }

  // ---------------------------------------------------------------- topology

  @Test
  void should_declare_the_queue_and_its_dead_letter_queue_when_the_application_starts() {
    assertThat(admin.getQueueInfo(QUEUE)).as("the work queue").isNotNull();
    assertThat(admin.getQueueInfo(DLQ)).as("the dead-letter queue").isNotNull();
  }

  @Test
  void should_receive_the_three_catalog_routing_keys_in_the_one_queue() {
    UUID event = uuid(3201);
    publish(event, T1);
    eventually(() -> projectionStatus(event).isPresent());

    send(
        RESCHEDULED_KEY,
        rescheduled(
            UUID.randomUUID(),
            event,
            T2,
            EVENT_STARTS.plus(Duration.ofDays(1)),
            ENDS.plus(Duration.ofDays(1))));
    eventually(() -> startsAt(event).equals(Optional.of(EVENT_STARTS.plus(Duration.ofDays(1)))));

    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), event, T3, "Venue unavailable"));
    eventually(() -> projectionStatus(event).equals(Optional.of("CANCELLED")));
    assertThat(deadLetterQueueIsEmpty()).isTrue();
  }

  @Test
  void should_not_receive_what_is_not_a_catalog_routing_key() {
    UUID event = uuid(3202);
    double before = consumed("EventPublished");

    send(
        "booking.booking.created.v1",
        published(UUID.randomUUID(), event, T1, EVENT_STARTS, ENDS, zones()));
    send(
        "catalog.event.deleted.v1",
        published(UUID.randomUUID(), event, T1, EVENT_STARTS, ENDS, zones()));
    awaitTheQueueToReachThisPoint();

    assertThat(projectionStatus(event)).isEmpty();
    assertThat(consumed("EventPublished") - before).as("only the marker").isEqualTo(1.0);
  }

  // ---------------------------------------------------------------- applying

  @Test
  void should_project_the_event_and_open_its_inventory_when_it_is_published() {
    UUID event = uuid(3203);
    UUID message = UUID.randomUUID();
    double before = consumed("EventPublished");

    send(PUBLISHED_KEY, published(message, event, T1, EVENT_STARTS, ENDS, zones()));

    eventually(() -> wasProcessed(message));
    assertThat(projectionStatus(event)).contains("OPEN");
    assertThat(startsAt(event)).contains(EVENT_STARTS);
    assertThat(zoneCount(event)).isEqualTo(2);
    assertThat(capacityOf(event, uuid(3101))).isEqualTo(20);
    assertThat(reservedOf(event, uuid(3101))).isZero();
    assertThat(consumed("EventPublished") - before).isEqualTo(1.0);
  }

  @Test
  void should_make_the_published_event_bookable_and_visible_in_the_availability() throws Exception {
    UUID event = uuid(3204);
    publish(event, T1);
    eventually(() -> projectionStatus(event).isPresent());

    availability(event)
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.onSale")
                .value(true))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.zones.length()")
                .value(2));
    create(alice, UUID.randomUUID(), bookingBody(event, new RequestedItem(uuid(3101), 2)))
        .andExpect(status().isCreated());
    assertThat(reservedOf(event, uuid(3101))).isEqualTo(2);
  }

  // ---------------------------------------------------------------- duplicates

  @Test
  void should_apply_a_message_once_when_the_producer_retries_after_the_broker_was_paused()
      throws Exception {
    UUID event = uuid(3205);
    UUID message = UUID.randomUUID();
    String body = published(message, event, T1, EVENT_STARTS, ENDS, zones());
    rabbit.execute(channel -> null); // a live connection, as the producer has
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pauseBroker();
      Future<?> sentWhilePaused = pool.submit(() -> send(PUBLISHED_KEY, body));
      resumeBroker();
      sentWhilePaused.get(30, TimeUnit.SECONDS);
    } finally {
      resumeBroker();
      pool.shutdownNow();
    }
    eventually(() -> wasProcessed(message));
    double consumedAfterFirst = consumed("EventPublished");

    // The producer never saw the confirmation of the first send, so it sends the same row again.
    send(PUBLISHED_KEY, body);
    eventually(() -> consumed("EventPublished") >= consumedAfterFirst + 1);

    assertThat(
            app.count(
                "SELECT count(*) FROM processed_message WHERE message_id = '" + message + "'"))
        .isEqualTo(1);
    assertThat(projectionStatus(event)).contains("OPEN");
    assertThat(zoneCount(event)).isEqualTo(2);
    assertThat(deadLetterQueueIsEmpty()).isTrue();
  }

  @Test
  void should_ignore_a_redelivered_message_id_even_when_its_content_is_different() {
    UUID event = uuid(3206);
    UUID message = UUID.randomUUID();
    send(PUBLISHED_KEY, published(message, event, T1, EVENT_STARTS, ENDS, zones()));
    eventually(() -> wasProcessed(message));

    send(
        PUBLISHED_KEY,
        published(
            message,
            event,
            T2,
            EVENT_STARTS,
            ENDS,
            List.of(
                new Zone(uuid(3101), "VIP", 999, "150.00"),
                new Zone(uuid(3102), "General", 100, "50.00"))));
    awaitTheQueueToReachThisPoint();

    assertThat(capacityOf(event, uuid(3101)))
        .as("the second copy of the id is not applied")
        .isEqualTo(20);
    assertThat(lastOccurredAt(event)).isEqualTo(T1);
  }

  @Test
  void should_keep_the_projection_when_a_different_message_carries_the_same_content() {
    UUID event = uuid(3207);
    publish(event, T1);
    eventually(() -> projectionStatus(event).isPresent());

    publish(event, T1);
    publish(event, T1);
    awaitTheQueueToReachThisPoint();

    assertThat(zoneCount(event)).isEqualTo(2);
    assertThat(capacityOf(event, uuid(3101))).isEqualTo(20);
    assertThat(lastOccurredAt(event)).isEqualTo(T1);
  }

  // ---------------------------------------------------------------- out of order

  @Test
  void should_ignore_an_older_publication_that_arrives_after_a_newer_one() {
    UUID event = uuid(3209);
    publish(event, T2);
    eventually(() -> projectionStatus(event).isPresent());

    send(
        PUBLISHED_KEY,
        published(
            UUID.randomUUID(),
            event,
            T1,
            EVENT_STARTS.plus(Duration.ofDays(3)),
            ENDS.plus(Duration.ofDays(3)),
            List.of(
                new Zone(uuid(3101), "VIP", 999, "150.00"),
                new Zone(uuid(3102), "General", 100, "50.00"))));
    awaitTheQueueToReachThisPoint();

    assertThat(lastOccurredAt(event)).isEqualTo(T2);
    assertThat(startsAt(event)).contains(EVENT_STARTS);
    assertThat(capacityOf(event, uuid(3101))).isEqualTo(20);
  }

  @Test
  void should_ignore_an_old_reschedule_that_arrives_after_a_newer_one() {
    UUID event = uuid(3210);
    publish(event, T1);
    Instant latest = EVENT_STARTS.plus(Duration.ofDays(14));
    send(
        RESCHEDULED_KEY,
        rescheduled(UUID.randomUUID(), event, T3, latest, latest.plusSeconds(3600)));
    eventually(() -> startsAt(event).equals(Optional.of(latest)));

    Instant older = EVENT_STARTS.plus(Duration.ofDays(7));
    send(
        RESCHEDULED_KEY, rescheduled(UUID.randomUUID(), event, T2, older, older.plusSeconds(3600)));
    awaitTheQueueToReachThisPoint();

    assertThat(startsAt(event)).contains(latest);
    assertThat(lastOccurredAt(event)).isEqualTo(T3);
  }

  @Test
  void should_keep_a_tombstone_when_the_cancellation_arrives_before_the_publication()
      throws Exception {
    UUID event = uuid(3211);

    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), event, T2, "Venue unavailable"));
    eventually(() -> projectionStatus(event).isPresent());
    publish(event, T1);
    publish(event, T3);
    awaitTheQueueToReachThisPoint();

    assertThat(projectionStatus(event)).contains("CANCELLED");
    assertThat(zoneCount(event)).as("a tombstone has no zones").isZero();
    assertThat(startsAt(event)).isEmpty();
    availability(event).andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------- catalog re-emission

  @Test
  void should_cancel_the_event_when_a_late_consumer_receives_the_replay_of_a_cancelled_event() {
    UUID event = uuid(3212);

    send(PUBLISHED_KEY, published(UUID.randomUUID(), event, T2, EVENT_STARTS, ENDS, zones()));
    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), event, T2, "Venue unavailable"));

    eventually(() -> projectionStatus(event).equals(Optional.of("CANCELLED")));
    assertThat(zoneCount(event)).as("the publication of the replay was applied first").isEqualTo(2);
    assertThat(lastOccurredAt(event)).isEqualTo(T2);
  }

  @Test
  void should_not_reopen_the_event_when_the_replay_arrives_in_the_other_order() {
    UUID event = uuid(3213);

    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), event, T2, "Venue unavailable"));
    send(PUBLISHED_KEY, published(UUID.randomUUID(), event, T2, EVENT_STARTS, ENDS, zones()));
    awaitTheQueueToReachThisPoint();

    assertThat(projectionStatus(event)).contains("CANCELLED");
  }

  @Test
  void should_keep_what_is_reserved_when_catalog_replays_the_publication() {
    UUID event = uuid(3214);
    UUID vip = uuid(3101);
    publish(event, T1);
    eventually(() -> projectionStatus(event).isPresent());
    app.tx.run(() -> app.inventory.reserve(event, List.of(new RequestedItem(vip, 5))));

    publish(event, T1);
    publish(event, T2);
    publish(event, T3);
    awaitTheQueueToReachThisPoint();

    assertThat(reservedOf(event, vip)).as("a replay never resets the sales").isEqualTo(5);
    assertThat(lastOccurredAt(event)).isEqualTo(T3);
  }

  @Test
  void should_not_reopen_a_cancelled_event_when_a_newer_replay_of_the_publication_arrives() {
    UUID event = uuid(3215);
    publish(event, T1);
    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), event, T2, "Venue unavailable"));
    eventually(() -> projectionStatus(event).equals(Optional.of("CANCELLED")));

    publish(event, T3);
    awaitTheQueueToReachThisPoint();

    assertThat(projectionStatus(event)).contains("CANCELLED");
    assertThat(lastOccurredAt(event)).as("the cancelled event is terminal").isEqualTo(T2);
  }

  // ---------------------------------------------------------------- the cascade

  @Test
  void should_cancel_every_booking_release_the_tickets_and_leave_the_refunds_to_the_reconciler()
      throws Exception {
    UUID pending = booked(alice, 2);
    UUID confirmed = booked(bob, 1);
    pay(bob, confirmed, "tok_approved").andExpect(status().isOk());
    int refundsBefore = gateway.refundCalls();

    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), EVENT, T2, "Venue unavailable"));

    eventually(() ->
        bookingStatus(pending).equals(BookingStatus.CANCELLED.name())
            && bookingStatus(confirmed).equals(BookingStatus.CANCELLED.name()));
    assertThat(app.reserved(VIP_ZONE)).isZero();
    assertThat(app.count("SELECT count(*) FROM outbox_event WHERE event_type = 'BookingCancelled'"))
        .isEqualTo(2);
    assertThat(
            app.count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'BookingCancelled'"
                    + " AND payload::text LIKE '%EVENT_CANCELLED%'"))
        .isEqualTo(2);
    assertThat(
            app.jdbc.queryForObject(
                "SELECT status FROM payment WHERE booking_id = ?", String.class, confirmed))
        .isEqualTo("REFUND_PENDING");
    assertThat(gateway.refundCalls())
        .as("the cascade never calls the gateway")
        .isEqualTo(refundsBefore);

    var report = reconcile.reconcile();

    assertThat(report.refundsCompleted()).isEqualTo(1);
    assertThat(
            app.jdbc.queryForObject(
                "SELECT status FROM payment WHERE booking_id = ?", String.class, confirmed))
        .isEqualTo("REFUNDED");
    assertThat(gateway.distinctRefunds()).isEqualTo(1);
    assertThat(
            app.count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'BookingPaymentRefunded'"
                    + " AND payload::text LIKE '%EVENT_CANCELLED%'"))
        .isEqualTo(1);
    app.assertInventoryInvariant();
  }

  @Test
  void should_not_touch_the_bookings_of_another_event_when_an_event_is_cancelled()
      throws Exception {
    UUID mine = booked(alice, 1);
    UUID other = uuid(3216);
    publish(other, T1);
    eventually(() -> projectionStatus(other).isPresent());

    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), other, T2, "Venue unavailable"));
    eventually(() -> projectionStatus(other).equals(Optional.of("CANCELLED")));
    awaitTheQueueToReachThisPoint();

    assertThat(bookingStatus(mine)).isEqualTo("PENDING");
  }

  private String bookingStatus(UUID bookingId) {
    return app.jdbc.queryForObject(
        "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
  }

  // ---------------------------------------------------------------- poison messages and the DLQ

  static Stream<Arguments> poison() {
    String valid = published(UUID.randomUUID(), uuid(3300), T1, EVENT_STARTS, ENDS, zones());
    return Stream.of(
        Arguments.of("broken JSON", PUBLISHED_KEY, "{ \"messageId\": "),
        Arguments.of(
            "a required field missing",
            PUBLISHED_KEY,
            mutated(valid, e -> ((ObjectNode) e.get("data")).remove("eventId"))),
        Arguments.of(
            "an unknown type", PUBLISHED_KEY, mutated(valid, e -> e.put("type", "EventExploded"))),
        Arguments.of(
            "an unsupported version", PUBLISHED_KEY, mutated(valid, e -> e.put("version", 2))),
        Arguments.of(
            "a zone without price",
            RESCHEDULED_KEY,
            mutated(
                valid,
                e ->
                    ((ObjectNode)
                            ((tools.jackson.databind.node.ArrayNode) e.get("data").get("zones"))
                                .get(0))
                        .remove("price"))),
        Arguments.of("not an object", CANCELLED_KEY, "[]"));
  }

  @ParameterizedTest(name = "{0} goes to the dead-letter queue without blocking the next message")
  @MethodSource("poison")
  void should_dead_letter_a_poison_message_at_once_and_go_on_with_the_next(
      String name, String routingKey, String poisonBody) {
    double failedBefore = failed("unreadable");
    double deadBefore = deadLettered();

    send(routingKey, poisonBody);
    UUID next = uuid(3301);
    publish(next, T1);

    eventually(() -> projectionStatus(next).isPresent());
    Message dead = deadLetter();
    assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(poisonBody);
    Map<String, Object> headers = dead.getMessageProperties().getHeaders();
    assertThat(headers).containsKey("x-exception-message");
    assertThat(failed("unreadable") - failedBefore).as("one attempt, no retries").isEqualTo(1.0);
    eventually(() -> deadLettered() - deadBefore == 1.0);
    assertThat(deadLetterQueueIsEmpty()).as("exactly one message").isTrue();
  }

  @Test
  void
      should_dead_letter_an_orphan_reschedule_after_the_retries_and_apply_the_replay_that_repairs_it() {
    UUID event = uuid(3217);
    Instant newStart = EVENT_STARTS.plus(Duration.ofDays(7));
    UUID orphan = UUID.randomUUID();
    double failedBefore = failed("EventRescheduled");
    double deadBefore = deadLettered();

    send(RESCHEDULED_KEY, rescheduled(orphan, event, T2, newStart, newStart.plusSeconds(3600)));

    Message dead = deadLetter();
    assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-exception-message");
    assertThat(failed("EventRescheduled") - failedBefore)
        .as("every attempt failed: the configured number of tries")
        .isEqualTo(5.0);
    eventually(() -> deadLettered() - deadBefore == 1.0);
    assertThat(wasProcessed(orphan)).as("a failed message is not marked as processed").isFalse();
    assertThat(projectionStatus(event)).isEmpty();

    UUID next = uuid(3219);
    publish(next, T1);
    eventually(() -> projectionStatus(next).isPresent());

    // Catalog's re-emission carries the current state (the new dates) and repairs what was lost.
    send(
        PUBLISHED_KEY,
        published(UUID.randomUUID(), event, T2, newStart, newStart.plusSeconds(3600), zones()));
    eventually(() -> startsAt(event).equals(Optional.of(newStart)));
  }

  @Test
  void should_count_the_messages_consumed_by_type() {
    UUID event = uuid(3218);
    double published = consumed("EventPublished");
    double rescheduled = consumed("EventRescheduled");
    double cancelled = consumed("EventCancelled");

    publish(event, T1);
    send(
        RESCHEDULED_KEY,
        rescheduled(
            UUID.randomUUID(), event, T2, EVENT_STARTS.plusSeconds(60), ENDS.plusSeconds(60)));
    send(CANCELLED_KEY, cancelled(UUID.randomUUID(), event, T3, "Venue unavailable"));

    eventually(() -> consumed("EventCancelled") - cancelled == 1.0);
    assertThat(consumed("EventPublished") - published).isEqualTo(1.0);
    assertThat(consumed("EventRescheduled") - rescheduled).isEqualTo(1.0);
  }

  // ---------------------------------------------------------------- broker

  private static void pauseBroker() {
    DockerClientFactory.instance().client().pauseContainerCmd(SharedRabbit.containerId()).exec();
  }

  private static void resumeBroker() {
    try {
      DockerClientFactory.instance()
          .client()
          .unpauseContainerCmd(SharedRabbit.containerId())
          .exec();
    } catch (com.github.dockerjava.api.exception.DockerException alreadyRunning) {
      // not paused: nothing to resume
    }
  }
}
