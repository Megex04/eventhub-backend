package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEntityMapperTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID ORGANIZER = new UUID(1, 1);

  private static TicketZone zone(String name, String price, int capacity) {
    return TicketZone.create(UUID.randomUUID(), name, Money.of(price, "USD"), capacity);
  }

  private static Event draft(TicketZone... zones) {
    return Event.create(
        UUID.randomUUID(),
        ORGANIZER,
        "Rock Fest",
        "Open air",
        "Estadio",
        NOW.plus(Duration.ofDays(30)),
        NOW.plus(Duration.ofDays(31)),
        List.of(zones),
        CLOCK);
  }

  @Test
  void should_round_trip_the_aggregate_when_mapped_to_an_entity_and_back() {
    Event event = draft(zone("General", "100.00", 500), zone("VIP", "250.50", 50));

    Event restored = EventEntityMapper.toDomain(EventEntityMapper.toNewEntity(event));

    assertThat(restored.id()).isEqualTo(event.id());
    assertThat(restored.organizerId()).isEqualTo(ORGANIZER);
    assertThat(restored.title()).isEqualTo("Rock Fest");
    assertThat(restored.description()).isEqualTo("Open air");
    assertThat(restored.venue()).isEqualTo("Estadio");
    assertThat(restored.startsAt()).isEqualTo(event.startsAt());
    assertThat(restored.endsAt()).isEqualTo(event.endsAt());
    assertThat(restored.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(restored.cancelReason()).isEmpty();
    assertThat(restored.createdAt()).isEqualTo(NOW);
    assertThat(restored.updatedAt()).isEqualTo(NOW);
    assertThat(restored.zones()).containsExactlyElementsOf(event.zones());
    assertThat(restored.zones().get(1).price()).isEqualTo(Money.of("250.50", "USD"));
  }

  @Test
  void should_store_the_position_of_each_zone_when_an_entity_is_created() {
    Event event = draft(zone("A", "1.00", 1), zone("B", "2.00", 2), zone("C", "3.00", 3));

    EventEntity entity = EventEntityMapper.toNewEntity(event);

    assertThat(entity.getZones())
        .extracting(TicketZoneEntity::getName, TicketZoneEntity::getPosition)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("A", (short) 0),
            org.assertj.core.groups.Tuple.tuple("B", (short) 1),
            org.assertj.core.groups.Tuple.tuple("C", (short) 2));
  }

  @Test
  void should_reuse_rows_of_surviving_zones_when_zones_are_added_and_removed() {
    Event event = draft(zone("A", "1.00", 1), zone("B", "2.00", 2), zone("C", "3.00", 3));
    EventEntity entity = EventEntityMapper.toNewEntity(event);
    TicketZoneEntity rowA = entity.getZones().get(0);
    TicketZoneEntity rowC = entity.getZones().get(2);

    event.removeZone(event.zones().get(1).id(), CLOCK);
    event.addZone(zone("D", "4.00", 4), CLOCK);
    EventEntityMapper.copyState(event, entity);

    assertThat(entity.getZones()).extracting(TicketZoneEntity::getName).contains("A", "C", "D");
    assertThat(entity.getZones()).hasSize(3);
    assertThat(entity.getZones()).contains(rowA, rowC);
    assertThat(rowC.getPosition()).isEqualTo((short) 1);
  }

  @Test
  void should_copy_status_and_cancel_reason_when_state_is_applied_to_an_entity() {
    Event event = draft(zone("A", "1.00", 1));
    EventEntity entity = EventEntityMapper.toNewEntity(event);
    event.publish(CLOCK);
    event.cancel("Venue unavailable", CLOCK);

    EventEntityMapper.copyState(event, entity);

    assertThat(entity.getStatus()).isEqualTo(EventStatus.CANCELLED);
    assertThat(entity.getCancelReason()).isEqualTo("Venue unavailable");
  }

  @Test
  void should_keep_money_scale_when_amount_comes_back_with_trailing_zeros() {
    Event event = draft(zone("A", "10.00", 1));

    Event restored = EventEntityMapper.toDomain(EventEntityMapper.toNewEntity(event));

    assertThat(restored.zones().getFirst().price().amount().scale()).isEqualTo(2);
  }
}
