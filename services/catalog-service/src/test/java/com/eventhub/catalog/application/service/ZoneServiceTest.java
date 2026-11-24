package com.eventhub.catalog.application.service;

import static com.eventhub.catalog.application.service.Fixture.NOW;
import static com.eventhub.catalog.application.service.Fixture.OWNER;
import static com.eventhub.catalog.application.service.Fixture.money;
import static com.eventhub.catalog.application.service.Fixture.zoneSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.port.in.AddZoneUseCase;
import com.eventhub.catalog.application.port.in.UpdateZoneUseCase;
import com.eventhub.catalog.application.port.in.ZoneSpec;
import com.eventhub.catalog.domain.BusinessRuleViolationException;
import com.eventhub.catalog.domain.BusinessRuleViolationException.Rule;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.InvalidStateException;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.domain.ZoneNotFoundException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneServiceTest {

  private final Fixture fixture = new Fixture();

  @Test
  void should_add_zone_with_generated_id_and_persist_when_event_is_draft() {
    Event stored = fixture.givenDraft(OWNER);
    fixture.clock.advance(Duration.ofMinutes(1));

    TicketZone zone =
        fixture.zones.addZone(new AddZoneUseCase.Command(stored.id(), OWNER, zoneSpec("VIP")));

    assertThat(zone.name()).isEqualTo("VIP");
    assertThat(zone.id()).isNotNull().isNotEqualTo(stored.id());
    assertThat(fixture.repository.findById(stored.id()).orElseThrow().zones())
        .extracting(TicketZone::name)
        .containsExactly("General", "VIP");
    assertThat(stored.updatedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
  }

  @Test
  void should_not_publish_domain_events_when_zones_change() {
    Event stored = fixture.givenDraft(OWNER);

    fixture.zones.addZone(new AddZoneUseCase.Command(stored.id(), OWNER, zoneSpec("VIP")));

    assertThat(fixture.publisher.published()).isEmpty();
  }

  @Test
  void should_reject_duplicate_zone_name_when_adding() {
    Event stored = fixture.givenDraft(OWNER);

    assertThatThrownBy(() ->
            fixture.zones.addZone(
                new AddZoneUseCase.Command(stored.id(), OWNER, zoneSpec("general"))))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class,
            e -> assertThat(e.rule()).isEqualTo(Rule.DUPLICATE_ZONE_NAME));
  }

  @Test
  void should_reject_zone_changes_when_event_is_published() {
    Event stored = fixture.givenPublished(OWNER);

    assertThatThrownBy(() ->
            fixture.zones.addZone(new AddZoneUseCase.Command(stored.id(), OWNER, zoneSpec("VIP"))))
        .isInstanceOf(InvalidStateException.class);
    assertThat(stored.zones()).hasSize(1);
  }

  @Test
  void should_update_zone_and_keep_its_id_when_event_is_draft() {
    Event stored = fixture.givenDraft(OWNER);
    UUID zoneId = stored.zones().getFirst().id();

    TicketZone updated =
        fixture.zones.updateZone(
            new UpdateZoneUseCase.Command(
                stored.id(), zoneId, OWNER, new ZoneSpec("Platinum", money("500.00"), 10)));

    assertThat(updated.id()).isEqualTo(zoneId);
    assertThat(fixture.repository.findById(stored.id()).orElseThrow().zones())
        .containsExactly(updated);
    assertThat(updated.name()).isEqualTo("Platinum");
  }

  @Test
  void should_report_zone_not_found_when_updating_an_unknown_zone() {
    Event stored = fixture.givenDraft(OWNER);

    assertThatThrownBy(() ->
            fixture.zones.updateZone(
                new UpdateZoneUseCase.Command(
                    stored.id(), UUID.randomUUID(), OWNER, zoneSpec("VIP"))))
        .isInstanceOf(ZoneNotFoundException.class);
  }

  @Test
  void should_remove_zone_when_event_is_draft() {
    Event stored = fixture.givenDraft(OWNER);
    UUID zoneId = stored.zones().getFirst().id();

    fixture.zones.removeZone(stored.id(), zoneId, OWNER);

    assertThat(fixture.repository.findById(stored.id()).orElseThrow().zones()).isEmpty();
  }

  @Test
  void should_report_zone_not_found_when_removing_an_unknown_zone() {
    Event stored = fixture.givenDraft(OWNER);

    assertThatThrownBy(() -> fixture.zones.removeZone(stored.id(), UUID.randomUUID(), OWNER))
        .isInstanceOf(ZoneNotFoundException.class);
    assertThat(stored.zones()).hasSize(1);
  }
}
