package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.ENDS_AT;
import static com.eventhub.catalog.domain.EventFixtures.NOW;
import static com.eventhub.catalog.domain.EventFixtures.ORGANIZER_ID;
import static com.eventhub.catalog.domain.EventFixtures.STARTS_AT;
import static com.eventhub.catalog.domain.EventFixtures.zone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventRestoreTest {

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
            NOW.plus(Duration.ofHours(2)));

    assertThat(event.id()).isEqualTo(id);
    assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
    assertThat(event.cancelReason()).contains("Venue unavailable");
    assertThat(event.zones()).containsExactly(zone);
    assertThat(event.createdAt()).isEqualTo(NOW);
    assertThat(event.updatedAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
  }

  @Test
  void should_not_record_domain_events_when_event_is_restored() {
    Event event =
        Event.restore(
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
            NOW);

    assertThat(event.pullDomainEvents()).isEmpty();
    assertThat(event.cancelReason()).isEmpty();
  }

  @Test
  void should_keep_restored_event_operable_when_state_allows_it() {
    Event event =
        Event.restore(
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
            NOW);

    event.cancel("Venue unavailable", EventFixtures.CLOCK);

    assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
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
                NOW))
        .isInstanceOf(NullPointerException.class);
  }
}
