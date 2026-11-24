package com.eventhub.catalog.application.service;

import static com.eventhub.catalog.application.service.Fixture.ENDS_AT;
import static com.eventhub.catalog.application.service.Fixture.OWNER;
import static com.eventhub.catalog.application.service.Fixture.STARTS_AT;
import static com.eventhub.catalog.application.service.Fixture.STRANGER;
import static com.eventhub.catalog.application.service.Fixture.zoneSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.exception.EventAccessDeniedException;
import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.CancelEventUseCase;
import com.eventhub.catalog.application.port.in.RescheduleEventUseCase;
import com.eventhub.catalog.application.port.in.UpdateEventUseCase;
import com.eventhub.catalog.application.port.in.UpdateZoneUseCase;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every write use case applies the same access rules: a missing event or one the requester cannot
 * see is 404, a visible event owned by someone else is 403.
 */
class EventOwnershipTest {

  @FunctionalInterface
  interface WriteOperation {
    void run(Fixture fixture, UUID eventId, UUID requesterId);
  }

  static Stream<Arguments> writeOperations() {
    return Stream.of(
        op(
            "update",
            (f, id, who) ->
                f.commands.update(
                    new UpdateEventUseCase.Command(
                        id, who, "New title", "", "New venue", STARTS_AT, ENDS_AT))),
        op("delete", (f, id, who) -> f.commands.delete(id, who)),
        op("publish", (f, id, who) -> f.commands.publish(id, who)),
        op(
            "cancel",
            (f, id, who) -> f.commands.cancel(new CancelEventUseCase.Command(id, who, "Reasons"))),
        op(
            "reschedule",
            (f, id, who) ->
                f.commands.reschedule(
                    new RescheduleEventUseCase.Command(
                        id, who, STARTS_AT, ENDS_AT, Optional.empty()))),
        op(
            "addZone",
            (f, id, who) -> f.zones.addZone(new AddZoneUseCase.Command(id, who, zoneSpec("VIP")))),
        op(
            "updateZone",
            (f, id, who) ->
                f.zones.updateZone(
                    new UpdateZoneUseCase.Command(id, UUID.randomUUID(), who, zoneSpec("VIP")))),
        op("removeZone", (f, id, who) -> f.zones.removeZone(id, UUID.randomUUID(), who)));
  }

  private static Arguments op(String name, WriteOperation operation) {
    return Arguments.of(Named.of(name, operation));
  }

  @ParameterizedTest
  @MethodSource("writeOperations")
  void should_report_not_found_when_event_does_not_exist(WriteOperation operation) {
    Fixture fixture = new Fixture();
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> operation.run(fixture, unknown, OWNER))
        .isInstanceOfSatisfying(
            EventNotFoundException.class, e -> assertThat(e.eventId()).isEqualTo(unknown));
  }

  @ParameterizedTest
  @MethodSource("writeOperations")
  void should_hide_the_event_when_someone_elses_draft_is_targeted(WriteOperation operation) {
    Fixture fixture = new Fixture();
    Event draft = fixture.givenDraft(OWNER);

    assertThatThrownBy(() -> operation.run(fixture, draft.id(), STRANGER))
        .isInstanceOf(EventNotFoundException.class);
    assertThat(draft.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(fixture.publisher.published()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("writeOperations")
  void should_hide_the_event_when_someone_elses_cancelled_event_is_targeted(
      WriteOperation operation) {
    Fixture fixture = new Fixture();
    Event cancelled = fixture.givenCancelled(OWNER);

    assertThatThrownBy(() -> operation.run(fixture, cancelled.id(), STRANGER))
        .isInstanceOf(EventNotFoundException.class);
  }

  @ParameterizedTest
  @MethodSource("writeOperations")
  void should_deny_access_when_someone_elses_published_event_is_targeted(WriteOperation operation) {
    Fixture fixture = new Fixture();
    Event published = fixture.givenPublished(OWNER);

    assertThatThrownBy(() -> operation.run(fixture, published.id(), STRANGER))
        .isInstanceOfSatisfying(
            EventAccessDeniedException.class,
            e -> assertThat(e.eventId()).isEqualTo(published.id()));
    assertThat(published.status()).isEqualTo(EventStatus.PUBLISHED);
    assertThat(fixture.publisher.published()).isEmpty();
  }
}
