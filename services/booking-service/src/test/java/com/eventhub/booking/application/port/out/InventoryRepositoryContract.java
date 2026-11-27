package com.eventhub.booking.application.port.out;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.InventoryInvariantException;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.domain.ZoneInventory;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public abstract class InventoryRepositoryContract {

  protected abstract InventoryRepository repository();

  private ZoneInventory zone(java.util.UUID zoneId) {
    return repository().findByEvent(EVENT).stream()
        .filter(z -> z.zoneId().equals(zoneId))
        .findFirst()
        .orElseThrow();
  }

  private void seed(int vip, int general) {
    repository().upsertCapacity(EVENT, VIP_ZONE, vip);
    repository().upsertCapacity(EVENT, GENERAL_ZONE, general);
  }

  @Test
  void should_start_with_nothing_reserved_when_a_zone_is_first_upserted() {
    seed(10, 5);

    assertThat(zone(VIP_ZONE).capacity()).isEqualTo(10);
    assertThat(zone(VIP_ZONE).reserved()).isZero();
    assertThat(repository().findByEvent(uuid(999))).isEmpty();
  }

  @Test
  void should_keep_what_is_reserved_when_the_capacity_is_upserted_again() {
    seed(10, 5);
    repository().reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 4)));

    repository().upsertCapacity(EVENT, VIP_ZONE, 10);

    assertThat(zone(VIP_ZONE).reserved()).isEqualTo(4);
  }

  @Test
  void should_reserve_up_to_the_capacity_when_enough_tickets_are_free() {
    seed(10, 5);

    repository().reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 10)));

    assertThat(zone(VIP_ZONE).available()).isZero();
  }

  @Test
  void should_reserve_nothing_in_any_zone_when_one_of_them_is_short() {
    seed(10, 2);

    assertThatThrownBy(() ->
            repository()
                .reserve(
                    EVENT,
                    List.of(new RequestedItem(VIP_ZONE, 3), new RequestedItem(GENERAL_ZONE, 3))))
        .isInstanceOf(InsufficientInventoryException.class);

    assertThat(zone(VIP_ZONE).reserved()).isZero();
    assertThat(zone(GENERAL_ZONE).reserved()).isZero();
  }

  @Test
  void should_report_insufficient_inventory_when_the_zone_has_no_inventory() {
    assertThatThrownBy(() -> repository().reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 1))))
        .isInstanceOf(InsufficientInventoryException.class);
  }

  @Test
  void should_release_what_was_reserved() {
    seed(10, 5);
    repository().reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 4)));

    repository().release(EVENT, List.of(new RequestedItem(VIP_ZONE, 3)));

    assertThat(zone(VIP_ZONE).reserved()).isEqualTo(1);
  }

  @Test
  void should_never_release_below_zero_and_leave_the_zone_untouched() {
    seed(10, 5);
    repository().reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 1)));

    assertThatThrownBy(() -> repository().release(EVENT, List.of(new RequestedItem(VIP_ZONE, 2))))
        .isInstanceOf(InventoryInvariantException.class);

    assertThat(zone(VIP_ZONE).reserved()).isEqualTo(1);
  }

  @Test
  void should_never_oversell_when_many_threads_reserve_at_once() throws Exception {
    seed(10, 5);
    int threads = 40;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger won = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> buyers =
          java.util.stream.IntStream.range(0, threads)
              .mapToObj(i ->
                  pool.submit(() -> {
                    start.await();
                    try {
                      repository().reserve(EVENT, List.of(new RequestedItem(VIP_ZONE, 1)));
                      won.incrementAndGet();
                    } catch (InsufficientInventoryException e) {
                      // the other 30 lose, as they should
                    }
                    return null;
                  }))
              .collect(java.util.stream.Collectors.toList());
      start.countDown();
      for (Future<?> buyer : buyers) {
        buyer.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(won.get()).isEqualTo(10);
    assertThat(zone(VIP_ZONE).reserved()).isEqualTo(10);
  }
}
