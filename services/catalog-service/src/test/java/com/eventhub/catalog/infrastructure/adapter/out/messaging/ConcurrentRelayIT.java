package com.eventhub.catalog.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

/**
 * Two relays (two instances of the service, in production) work on the same table at once. Row
 * locks with SKIP LOCKED must hand each row to exactly one of them.
 */
class ConcurrentRelayIT extends MessagingIntegrationTest {

  private static final int EVENTS = 24;

  @Test
  void should_deliver_every_row_exactly_once_when_two_relays_run_at_the_same_time()
      throws Exception {
    Set<UUID> created = new HashSet<>();
    for (int i = 0; i < EVENTS; i++) {
      created.add(createPublished(organizer()));
    }
    CyclicBarrier start = new CyclicBarrier(2);
    Callable<Integer> relayLoop =
        () -> {
          start.await(20, TimeUnit.SECONDS);
          int sent = 0;
          for (int pass = 0; pass < 40; pass++) {
            int inThisPass = relay.publishPending();
            sent += inThisPass;
            if (inThisPass == 0) {
              break;
            }
          }
          return sent;
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    int sent;
    try {
      Future<Integer> first = pool.submit(relayLoop);
      Future<Integer> second = pool.submit(relayLoop);
      sent = first.get(60, TimeUnit.SECONDS) + second.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    // A relay that finds only rows the other one holds locked stops early; finish what is left.
    sent += drain();

    assertThat(sent).as("rows confirmed by the two relays together").isEqualTo(EVENTS);
    List<Message> delivered = receive(EVENTS);
    assertThat(queueIsEmpty()).as("no duplicate behind the expected messages").isTrue();
    assertThat(delivered.stream().map(m -> field(m, "$.messageId")).distinct()).hasSize(EVENTS);
    Set<String> deliveredEvents = new HashSet<>();
    delivered.forEach(m -> deliveredEvents.add(field(m, "$.data.eventId")));
    assertThat(deliveredEvents)
        .containsExactlyInAnyOrderElementsOf(created.stream().map(UUID::toString).toList());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
        .isZero();
  }
}
