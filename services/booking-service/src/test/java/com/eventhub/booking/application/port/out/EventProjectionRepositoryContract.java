package com.eventhub.booking.application.port.out;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.domain.EventProjection;
import com.eventhub.booking.domain.EventSaleStatus;
import org.junit.jupiter.api.Test;

public abstract class EventProjectionRepositoryContract {

  protected abstract EventProjectionRepository repository();

  @Test
  void should_find_nothing_when_the_event_was_never_projected() {
    assertThat(repository().findById(EVENT)).isEmpty();
  }

  @Test
  void should_round_trip_the_projection_with_its_zones_and_timestamp() {
    repository().save(openProjection());

    EventProjection found = repository().findById(EVENT).orElseThrow();

    assertThat(found.status()).isEqualTo(EventSaleStatus.OPEN);
    assertThat(found.startsAt()).contains(EVENT_STARTS);
    assertThat(found.endsAt()).isPresent();
    assertThat(found.zones()).containsExactlyInAnyOrder(VIP, GENERAL);
    assertThat(found.lastOccurredAt()).isEqualTo(NOW);
  }

  @Test
  void should_round_trip_a_tombstone_without_dates_or_zones() {
    repository().save(EventProjection.cancelledTombstone(EVENT, NOW));

    EventProjection found = repository().findById(EVENT).orElseThrow();

    assertThat(found.isTombstone()).isTrue();
    assertThat(found.status()).isEqualTo(EventSaleStatus.CANCELLED);
    assertThat(found.startsAt()).isEmpty();
    assertThat(found.zones()).isEmpty();
    assertThat(found.lastOccurredAt()).isEqualTo(NOW);
  }

  @Test
  void should_replace_the_stored_projection_when_it_is_saved_again() {
    repository().save(openProjection());
    EventProjection cancelled = repository().findById(EVENT).orElseThrow();
    cancelled.applyCancelled(NOW.plusSeconds(30));

    repository().save(cancelled);

    EventProjection found = repository().findById(EVENT).orElseThrow();
    assertThat(found.status()).isEqualTo(EventSaleStatus.CANCELLED);
    assertThat(found.lastOccurredAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(found.zones()).containsExactlyInAnyOrder(VIP, GENERAL);
  }
}
