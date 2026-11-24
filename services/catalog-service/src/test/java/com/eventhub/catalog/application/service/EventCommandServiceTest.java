package com.eventhub.catalog.application.service;

import static com.eventhub.catalog.application.service.Fixture.ENDS_AT;
import static com.eventhub.catalog.application.service.Fixture.NOW;
import static com.eventhub.catalog.application.service.Fixture.OWNER;
import static com.eventhub.catalog.application.service.Fixture.STARTS_AT;
import static com.eventhub.catalog.application.service.Fixture.zoneSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.port.in.CancelEventUseCase;
import com.eventhub.catalog.application.port.in.CreateEventUseCase;
import com.eventhub.catalog.application.port.in.RescheduleEventUseCase;
import com.eventhub.catalog.application.port.in.UpdateEventUseCase;
import com.eventhub.catalog.domain.BusinessRuleViolationException;
import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventCancelled;
import com.eventhub.catalog.domain.EventPublished;
import com.eventhub.catalog.domain.EventRescheduled;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.InvalidStateException;
import com.eventhub.catalog.domain.InvalidValueException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventCommandServiceTest {

  private final Fixture fixture = new Fixture();

  private static CreateEventUseCase.Command createCommand(
      List<com.eventhub.catalog.application.port.in.ZoneSpec> zones) {
    return new CreateEventUseCase.Command(
        OWNER, "Rock Fest", "Open air", "Estadio Nacional", STARTS_AT, ENDS_AT, zones);
  }

  @Nested
  class Create {

    @Test
    void should_create_draft_owned_by_requester_when_command_is_valid() {
      Event event = fixture.commands.create(createCommand(List.of()));

      assertThat(event.organizerId()).isEqualTo(OWNER);
      assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
      assertThat(event.createdAt()).isEqualTo(NOW);
    }

    @Test
    void should_generate_ids_through_the_port_when_event_and_zones_are_created() {
      Event event =
          fixture.commands.create(createCommand(List.of(zoneSpec("General"), zoneSpec("VIP"))));

      assertThat(event.zones()).hasSize(2);
      assertThat(event.zones()).extracting(z -> z.id()).doesNotHaveDuplicates();
      assertThat(event.zones().getFirst().id()).isNotEqualTo(event.id());
    }

    @Test
    void should_persist_the_event_when_creation_succeeds() {
      Event event = fixture.commands.create(createCommand(List.of(zoneSpec("General"))));

      assertThat(fixture.repository.findById(event.id())).containsSame(event);
    }

    @Test
    void should_not_publish_domain_events_when_event_is_created() {
      fixture.commands.create(createCommand(List.of(zoneSpec("General"))));

      assertThat(fixture.publisher.published()).isEmpty();
    }

    @Test
    void should_reject_and_persist_nothing_when_data_is_invalid() {
      CreateEventUseCase.Command invalid =
          new CreateEventUseCase.Command(OWNER, "ab", "", "Estadio", STARTS_AT, ENDS_AT, List.of());

      assertThatThrownBy(() -> fixture.commands.create(invalid))
          .isInstanceOf(InvalidValueException.class);
      assertThat(fixture.repository.size()).isZero();
    }

    @Test
    void should_reject_and_persist_nothing_when_zones_exceed_the_limit() {
      List<com.eventhub.catalog.application.port.in.ZoneSpec> tooMany =
          IntStream.rangeClosed(1, 21).mapToObj(i -> zoneSpec("Zone " + i)).toList();

      assertThatThrownBy(() -> fixture.commands.create(createCommand(tooMany)))
          .isInstanceOfSatisfying(
              BusinessRuleViolationException.class,
              e -> assertThat(e.rule()).isEqualTo(Rule.ZONE_LIMIT_EXCEEDED));
      assertThat(fixture.repository.size()).isZero();
    }
  }

  @Nested
  class Update {

    private UpdateEventUseCase.Command updateCommand(Event event, String title) {
      return new UpdateEventUseCase.Command(
          event.id(), OWNER, title, "New description", "New venue", STARTS_AT, ENDS_AT);
    }

    @Test
    void should_update_and_persist_when_requester_owns_a_draft() {
      Event stored = fixture.givenDraft(OWNER);
      fixture.clock.advance(Duration.ofMinutes(5));

      Event updated = fixture.commands.update(updateCommand(stored, "New title"));

      assertThat(updated.title()).isEqualTo("New title");
      assertThat(updated.venue()).isEqualTo("New venue");
      assertThat(updated.updatedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
      assertThat(fixture.repository.findById(stored.id())).containsSame(updated);
    }

    @Test
    void should_reject_update_when_event_is_published() {
      Event stored = fixture.givenPublished(OWNER);

      assertThatThrownBy(() -> fixture.commands.update(updateCommand(stored, "New title")))
          .isInstanceOf(InvalidStateException.class);
      assertThat(stored.title()).isEqualTo("Rock Fest");
    }

    @Test
    void should_reject_update_when_values_are_invalid() {
      Event stored = fixture.givenDraft(OWNER);

      assertThatThrownBy(() -> fixture.commands.update(updateCommand(stored, "ab")))
          .isInstanceOf(InvalidValueException.class);
    }
  }

  @Nested
  class Delete {

    @Test
    void should_remove_the_event_when_requester_owns_a_draft() {
      Event stored = fixture.givenDraft(OWNER);

      fixture.commands.delete(stored.id(), OWNER);

      assertThat(fixture.repository.contains(stored.id())).isFalse();
    }

    @Test
    void should_keep_the_event_when_it_is_published() {
      Event stored = fixture.givenPublished(OWNER);

      assertThatThrownBy(() -> fixture.commands.delete(stored.id(), OWNER))
          .isInstanceOf(InvalidStateException.class);
      assertThat(fixture.repository.contains(stored.id())).isTrue();
    }

    @Test
    void should_keep_the_event_when_it_is_cancelled() {
      Event stored = fixture.givenCancelled(OWNER);

      assertThatThrownBy(() -> fixture.commands.delete(stored.id(), OWNER))
          .isInstanceOf(InvalidStateException.class);
      assertThat(fixture.repository.contains(stored.id())).isTrue();
    }
  }

  @Nested
  class Publish {

    @Test
    void should_publish_persist_and_emit_event_published_when_draft_is_valid() {
      Event stored = fixture.givenDraft(OWNER);

      Event published = fixture.commands.publish(stored.id(), OWNER);

      assertThat(published.status()).isEqualTo(EventStatus.PUBLISHED);
      assertThat(fixture.repository.findById(stored.id()))
          .hasValueSatisfying(e -> assertThat(e.status()).isEqualTo(EventStatus.PUBLISHED));
      assertThat(fixture.publisher.published())
          .singleElement()
          .isInstanceOfSatisfying(
              EventPublished.class,
              e -> {
                assertThat(e.eventId()).isEqualTo(stored.id());
                assertThat(e.zones()).hasSize(1);
              });
    }

    @Test
    void should_emit_nothing_when_publish_violates_a_business_rule() {
      Event stored = fixture.givenDraft(OWNER);
      fixture.clock.advance(Duration.ofDays(31));

      assertThatThrownBy(() -> fixture.commands.publish(stored.id(), OWNER))
          .isInstanceOfSatisfying(
              BusinessRuleViolationException.class,
              e -> assertThat(e.rule()).isEqualTo(Rule.STARTS_AT_NOT_IN_FUTURE));
      assertThat(fixture.publisher.published()).isEmpty();
      assertThat(stored.status()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void should_emit_a_single_event_when_publish_is_requested_twice() {
      Event stored = fixture.givenDraft(OWNER);
      fixture.commands.publish(stored.id(), OWNER);

      assertThatThrownBy(() -> fixture.commands.publish(stored.id(), OWNER))
          .isInstanceOf(InvalidStateException.class);
      assertThat(fixture.publisher.published()).hasSize(1);
    }
  }

  @Nested
  class Cancel {

    @Test
    void should_cancel_persist_and_emit_event_cancelled_when_event_is_published() {
      Event stored = fixture.givenPublished(OWNER);

      Event cancelled =
          fixture.commands.cancel(
              new CancelEventUseCase.Command(stored.id(), OWNER, "Artist is sick"));

      assertThat(cancelled.status()).isEqualTo(EventStatus.CANCELLED);
      assertThat(cancelled.cancelReason()).contains("Artist is sick");
      assertThat(fixture.publisher.published())
          .singleElement()
          .isInstanceOfSatisfying(
              EventCancelled.class, e -> assertThat(e.reason()).isEqualTo("Artist is sick"));
    }

    @Test
    void should_emit_nothing_when_reason_is_invalid() {
      Event stored = fixture.givenPublished(OWNER);

      assertThatThrownBy(() ->
              fixture.commands.cancel(new CancelEventUseCase.Command(stored.id(), OWNER, "no")))
          .isInstanceOf(InvalidValueException.class);
      assertThat(fixture.publisher.published()).isEmpty();
    }

    @Test
    void should_reject_cancel_when_event_is_still_a_draft() {
      Event stored = fixture.givenDraft(OWNER);

      assertThatThrownBy(() ->
              fixture.commands.cancel(
                  new CancelEventUseCase.Command(stored.id(), OWNER, "Not needed")))
          .isInstanceOf(InvalidStateException.class);
    }
  }

  @Nested
  class Reschedule {

    private final Instant newStart = STARTS_AT.plus(Duration.ofDays(7));
    private final Instant newEnd = newStart.plus(Duration.ofHours(5));

    @Test
    void should_reschedule_keeping_the_venue_when_none_is_given() {
      Event stored = fixture.givenPublished(OWNER);

      Event rescheduled =
          fixture.commands.reschedule(
              new RescheduleEventUseCase.Command(
                  stored.id(), OWNER, newStart, newEnd, Optional.empty()));

      assertThat(rescheduled.startsAt()).isEqualTo(newStart);
      assertThat(rescheduled.venue()).isEqualTo("Estadio Nacional");
      assertThat(fixture.publisher.published())
          .singleElement()
          .isInstanceOfSatisfying(
              EventRescheduled.class, e -> assertThat(e.venue()).isEqualTo("Estadio Nacional"));
    }

    @Test
    void should_reschedule_to_the_new_venue_when_one_is_given() {
      Event stored = fixture.givenPublished(OWNER);

      Event rescheduled =
          fixture.commands.reschedule(
              new RescheduleEventUseCase.Command(
                  stored.id(), OWNER, newStart, newEnd, Optional.of("Arena Lima")));

      assertThat(rescheduled.venue()).isEqualTo("Arena Lima");
    }

    @Test
    void should_emit_nothing_when_event_has_already_started() {
      Event stored = fixture.givenPublished(OWNER);
      fixture.clock.advance(Duration.ofDays(31));

      assertThatThrownBy(() ->
              fixture.commands.reschedule(
                  new RescheduleEventUseCase.Command(
                      stored.id(),
                      OWNER,
                      newStart.plus(Duration.ofDays(60)),
                      newEnd.plus(Duration.ofDays(60)),
                      Optional.empty())))
          .isInstanceOfSatisfying(
              BusinessRuleViolationException.class,
              e -> assertThat(e.rule()).isEqualTo(Rule.EVENT_ALREADY_STARTED));
      assertThat(fixture.publisher.published()).isEmpty();
    }
  }
}
