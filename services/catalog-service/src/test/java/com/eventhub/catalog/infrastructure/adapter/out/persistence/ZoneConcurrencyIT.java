package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.service.EventCommandService;
import com.eventhub.catalog.application.service.ZoneService;
import com.eventhub.catalog.domain.Money;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/**
 * Zone changes are decided on a loaded aggregate, so two of them (or one of them and a publish)
 * that start from the same version must never both commit.
 */
class ZoneConcurrencyIT extends ConcurrencyIT {

  private AddZoneUseCase.Command addZone(UUID eventId, String name) {
    return new AddZoneUseCase.Command(
        eventId, ORGANIZER, new ZoneSpec(name, Money.of("80.00", "PEN"), 50));
  }

  private long versionOf(UUID eventId) {
    return jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId);
  }

  private int zoneCount(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ticket_zone WHERE event_id = ?", Integer.class, eventId);
  }

  @Test
  void should_let_exactly_one_zone_win_when_two_adds_with_different_names_race()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ZoneService racer1 = racingZones(barrier, clock);
    ZoneService racer2 = racingZones(barrier, clock);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> racer1.addZone(addZone(eventId, "VIP"))),
            () -> transaction().execute(status -> racer2.addZone(addZone(eventId, "Platea"))));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    assertThat(zoneCount(eventId)).isEqualTo(2);
    assertThat(versionOf(eventId)).as("one winner, one increment").isEqualTo(1);
  }

  @Test
  void should_keep_a_single_zone_when_two_adds_with_the_same_name_race()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ZoneService racer1 = racingZones(barrier, clock);
    ZoneService racer2 = racingZones(barrier, clock);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> racer1.addZone(addZone(eventId, "VIP"))),
            () -> transaction().execute(status -> racer2.addZone(addZone(eventId, "VIP"))));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ticket_zone WHERE event_id = ? AND name_key = 'vip'",
                Integer.class,
                eventId))
        .isEqualTo(1);
  }

  /**
   * With a frozen clock the second writer sets the same {@code updated_at} the row already has, so
   * a version bump that depended on the row being dirty would be skipped and both writes would win.
   */
  @Test
  void should_still_let_exactly_one_zone_win_when_the_clock_does_not_move()
      throws InterruptedException {
    Clock frozen = Clock.fixed(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    UUID eventId = createDraftWithOneZone(frozen);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ZoneService racer1 = racingZones(barrier, frozen);
    ZoneService racer2 = racingZones(barrier, frozen);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> racer1.addZone(addZone(eventId, "VIP"))),
            () -> transaction().execute(status -> racer2.addZone(addZone(eventId, "Platea"))));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    assertThat(zoneCount(eventId)).isEqualTo(2);
    assertThat(versionOf(eventId)).as("one winner, one increment").isEqualTo(1);
  }

  @Test
  void should_never_publish_a_stale_zone_list_when_an_add_races_with_publish()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ZoneService adding = racingZones(barrier, clock);
    EventCommandService publishing = racingCommands(barrier);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> adding.addZone(addZone(eventId, "VIP"))),
            () -> transaction().execute(status -> publishing.publish(eventId, ORGANIZER)));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    String status =
        jdbc.queryForObject("SELECT status FROM event WHERE id = ?", String.class, eventId);
    List<String> published = jdbc.queryForList("SELECT event_type FROM outbox_event", String.class);
    assertThat(versionOf(eventId)).as("one winner, one increment").isEqualTo(1);
    if (status.equals("PUBLISHED")) {
      assertThat(zoneCount(eventId)).isEqualTo(1);
      assertThat(published).containsExactly("EventPublished");
      assertThat(
              jdbc.queryForObject(
                  "SELECT jsonb_array_length(payload -> 'data' -> 'zones') FROM outbox_event",
                  Integer.class))
          .isEqualTo(1);
    } else {
      assertThat(status).isEqualTo("DRAFT");
      assertThat(zoneCount(eventId)).isEqualTo(2);
      assertThat(published).isEmpty();
    }
  }
}
