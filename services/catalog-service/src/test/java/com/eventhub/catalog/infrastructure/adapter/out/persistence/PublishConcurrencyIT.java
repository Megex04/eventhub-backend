package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.service.EventCommandService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

class PublishConcurrencyIT extends ConcurrencyIT {

  @Test
  void should_let_exactly_one_publish_win_and_record_a_single_event_when_two_race_on_one_event()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    EventCommandService racer1 = racingCommands(barrier);
    EventCommandService racer2 = racingCommands(barrier);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> racer1.publish(eventId, ORGANIZER)),
            () -> transaction().execute(status -> racer2.publish(eventId, ORGANIZER)));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    Map<String, Object> stored =
        jdbc.queryForMap("SELECT status, version FROM event WHERE id = ?", eventId);
    assertThat(stored.get("status")).isEqualTo("PUBLISHED");
    assertThat(stored.get("version")).isEqualTo(1L);
    assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event", String.class))
        .containsExactly("EventPublished");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void should_keep_the_outbox_consistent_when_publish_and_delete_race_on_one_draft()
      throws InterruptedException {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier barrier = new CyclicBarrier(2);
    EventCommandService publishing = racingCommands(barrier);
    EventCommandService deleting = racingCommands(barrier);

    Outcome outcome =
        race(
            () -> transaction().execute(status -> publishing.publish(eventId, ORGANIZER)),
            () ->
                transaction()
                    .execute(status -> {
                      deleting.delete(eventId, ORGANIZER);
                      return null;
                    }));

    assertThat(outcome.count(Result.SUCCESS)).as(outcome.toString()).isEqualTo(1);
    assertThat(outcome.count(Result.CONFLICT)).as(outcome.toString()).isEqualTo(1);
    Integer remaining =
        jdbc.queryForObject("SELECT count(*) FROM event WHERE id = ?", Integer.class, eventId);
    Integer outboxRows = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
    if (remaining == 1) {
      assertThat(jdbc.queryForObject("SELECT status FROM event", String.class))
          .isEqualTo("PUBLISHED");
      assertThat(outboxRows).isEqualTo(1);
    } else {
      assertThat(outboxRows).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_zone", Integer.class)).isZero();
    }
  }

  @Test
  void should_publish_normally_when_there_is_no_competing_transaction() {
    UUID eventId = createDraftWithOneZone();
    CyclicBarrier alone = new CyclicBarrier(1);
    EventCommandService service = racingCommands(alone);

    transaction().execute(status -> service.publish(eventId, ORGANIZER));

    assertThat(jdbc.queryForObject("SELECT version FROM event WHERE id = ?", Long.class, eventId))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
        .isEqualTo(1);
  }
}
