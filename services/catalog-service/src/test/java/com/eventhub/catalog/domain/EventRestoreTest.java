package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.CLOCK;
import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.ORGANIZER_ID;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.draftEventWithZone;
import static com.eventhub.catalog.domain.EventFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventRestoreTest {

  private static Event restoredPublished(long version) {
    return Event.restore(
        UUID.randomUUID(),
        ORGANIZER_ID,
        "Rock Fest",
        "",
        "Estadio",
        STARTS_AT,
        ENDS_AT,
        EventStatus.PUBLISHED,
        Optional.empty(),
        List.of(zone("General")),
        NOW,
        NOW,
        version);
  }

  @Test
  void should_rebuild_the_exact_persisted_state_when_event_is_restored() {
    UUID id = UUID.randomUUID();
    TicketZone zone = zone("General");

    Event event =
        Event.restore(
            id,
            ORGANIZER_ID,
            "Rock Fest",
            "Open air",
            "Estadio",
            STARTS_AT,
            ENDS_AT,
            EventStatus.CANCELLED,
            Optional.of("Venue unavailable"),
            List.of(zone),
            NOW,
            NOW.plus(Duration.ofHours(2)),
            7L);

    assertThat(event.id()).isEqualTo(id);
    assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
    assertThat(event.cancelReason()).contains("Venue unavailable");
    assertThat(event.zones()).containsExactly(zone);
    assertThat(event.createdAt()).isEqualTo(NOW);
    assertThat(event.updatedAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    assertThat(event.version()).isEqualTo(7L);
  }

  @Test
  void should_not_record_domain_events_when_event_is_restored() {
    Event event = restoredPublished(0L);

    assertThat(event.pullDomainEvents()).isEmpty();
    assertThat(event.cancelReason()).isEmpty();
  }

  @Test
  void should_keep_restored_event_operable_when_state_allows_it() {
    Event event = restoredPublished(0L);

    event.cancel("Venue unavailable", CLOCK);

    assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
  }

  @Test
  void should_start_at_version_zero_when_event_is_created() {
    assertThat(draftEventWithZone().version()).isZero();
  }

  @Test
  void should_keep_the_loaded_version_when_domain_operations_run() {
    Event event = restoredPublished(5L);

    event.cancel("Venue unavailable", CLOCK);

    assertThat(event.version()).isEqualTo(5L);
  }

  @Test
  void should_keep_the_loaded_version_when_zones_change() {
    Event event = draftEventWithZone();

    event.addZone(zone("VIP"), CLOCK);
    event.publish(CLOCK);

    assertThat(event.version()).isZero();
  }

  @Test
  void should_fail_fast_when_persisted_state_is_incomplete() {
    assertThatThrownBy(() ->
            Event.restore(
                null,
                ORGANIZER_ID,
                "Rock Fest",
                "",
                "Estadio",
                STARTS_AT,
                ENDS_AT,
                EventStatus.DRAFT,
                Optional.empty(),
                List.of(),
                NOW,
                NOW,
                0L))
        .isInstanceOf(NullPointerException.class);
  }
}
