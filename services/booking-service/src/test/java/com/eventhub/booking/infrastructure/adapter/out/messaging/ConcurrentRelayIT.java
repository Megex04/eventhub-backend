package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.domain.RequestedItem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

/**
 * Two relays (two instances of the service, in production) work on the same table at once. Row
 * locks with SKIP LOCKED must hand each row to exactly one of them, and a relay must never wait for
 * rows the other one holds.
 */
class ConcurrentRelayIT extends MessagingIT {

  private static final int BOOKINGS = 24;

  private UUID bookGeneral() throws Exception {
    return idOf(
        create(alice, UUID.randomUUID(), bookingBody(EVENT, new RequestedItem(GENERAL_ZONE, 1)))
            .andExpect(status().isCreated())
            .andReturn());
  }

  @Test
  void should_deliver_every_row_exactly_once_when_two_relays_run_at_the_same_time()
      throws Exception {
    Set<UUID> created = new HashSet<>();
    for (int i = 0; i < BOOKINGS; i++) {
      created.add(bookGeneral());
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

    assertThat(sent).as("rows confirmed by the two relays together").isEqualTo(BOOKINGS);
    List<Message> delivered = receive(BOOKINGS);
    assertThat(queueIsEmpty()).as("no duplicate behind the expected messages").isTrue();
    assertThat(delivered.stream().map(m -> field(m, "$.messageId")).distinct()).hasSize(BOOKINGS);
    Set<String> deliveredBookings = new HashSet<>();
    delivered.forEach(m -> deliveredBookings.add(field(m, "$.data.bookingId")));
    assertThat(deliveredBookings)
        .containsExactlyInAnyOrderElementsOf(created.stream().map(UUID::toString).toList());
    assertThat(app.count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")).isZero();
  }

  @Test
  void should_not_wait_for_rows_another_relay_is_holding_locked() throws Exception {
    for (int i = 0; i < 10; i++) {
      bookGeneral();
    }
    CountDownLatch holdsItsRows = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean firstSend = new AtomicBoolean(true);
    broker.beforePublish(message -> {
      if (firstSend.compareAndSet(true, false)) {
        holdsItsRows.countDown();
        await(release);
      }
    });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> blocked = pool.submit(() -> relay.publishPending());
      assertThat(holdsItsRows.await(30, TimeUnit.SECONDS)).isTrue();

      Future<Integer> other = pool.submit(() -> relay.publishPending());
      int sentByTheOther = other.get(15, TimeUnit.SECONDS);

      assertThat(sentByTheOther)
          .as("the second relay took the rows the first one does not hold, without waiting")
          .isEqualTo(5);
      assertThat(app.count("SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL"))
          .as("the first relay's rows are still unpublished")
          .isEqualTo(5);

      release.countDown();
      assertThat(blocked.get(30, TimeUnit.SECONDS)).isEqualTo(5);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertThat(receive(10).stream().map(m -> field(m, "$.messageId")).distinct()).hasSize(10);
    assertThat(queueIsEmpty()).isTrue();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch never opened");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
