package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.UpdateEventUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.application.service.EventCommandService;
import com.eventhub.catalog.application.service.ZoneService;
import com.eventhub.catalog.domain.Money;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/**
 * How the version of an event moves. Every successful save advances it by exactly one, whatever
 * changed (columns, only zones, both), and two writers that started from the same version cannot
 * both succeed. See ADR 0004.
 */
class VersioningIT extends ConcurrencyIT {

  private long versionOf(UUID eventId) {
    return jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId);
  }

  private UpdateEventUseCase.Command retitle(UUID eventId, String title) {
    var row = jdbc.queryForMap("SELECT starts_at, ends_at FROM event WHERE id = ?", eventId);
    return new UpdateEventUseCase.Command(
        eventId,
        ORGANIZER,
        title,
        "",
        "Estadio",
        ((Timestamp) row.get("starts_at")).toInstant(),
        ((Timestamp) row.get("ends_at")).toInstant());
  }

  private AddZoneUseCase.Command zone(UUID eventId, String name) {
    return new AddZoneUseCase.Command(
        eventId, ORGANIZER, new ZoneSpec(name, Money.of("80.00", "PEN"), 50));
  }

  @Test
  void should_advance_the_version_by_exactly_one_when_columns_change() {
    UUID eventId = createDraftWithOneZone();
    EventCommandService service = racingCommands(new CyclicBarrier(1));
    assertThat(versionOf(eventId)).isZero();

    transaction().execute(status -> service.update(retitle(eventId, "First title")));
    assertThat(versionOf(eventId)).isEqualTo(1);

    transaction().execute(status -> service.update(retitle(eventId, "Second title")));
    assertThat(versionOf(eventId)).isEqualTo(2);
  }

  @Test
  void should_advance_the_version_by_exactly_one_when_only_the_zones_change() {
    Clock frozen = Clock.fixed(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    UUID eventId = createDraftWithOneZone(frozen);
    Timestamp updatedBefore =
        jdbc.queryForObject("SELECT updated_at FROM event WHERE id = ?", Timestamp.class, eventId);
    ZoneService zones = racingZones(new CyclicBarrier(1), frozen);

    transaction().execute(status -> zones.addZone(zone(eventId, "VIP")));

    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM event WHERE id = ?", Timestamp.class, eventId))
        .as("no column of the row changed: the version moved without the row being dirty")
        .isEqualTo(updatedBefore);
    assertThat(versionOf(eventId)).isEqualTo(1);
  }

  @Test
  void should_let_exactly_one_win_when_two_title_only_updates_race() throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    EventCommandService racer1 = racingCommands(barrier);
    EventCommandService racer2 = racingCommands(barrier);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> racer1.update(retitle(eventId, "Title A"))),
            () -> transaction().execute(status -> racer2.update(retitle(eventId, "Title B"))));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    assertThat(versionOf(eventId)).as("one winner, one increment").isEqualTo(1);
  }
}
