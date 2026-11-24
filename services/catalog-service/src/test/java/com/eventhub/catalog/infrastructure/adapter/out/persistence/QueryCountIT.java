package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import com.eventhub.catalog.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Guards against N+1: statement counts must not depend on how many zones or events exist. */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCountIT extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final int ZONES_PER_EVENT = 5;

  @Autowired private EventRepository repository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Statistics statistics;

  @BeforeEach
  void enableStatistics() {
    statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
  }

  private List<UUID> saveEvents(int count) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      List<TicketZone> zones = new ArrayList<>();
      for (int z = 0; z < ZONES_PER_EVENT; z++) {
        zones.add(TicketZone.create(UUID.randomUUID(), "Zone " + z, Money.of("10.00", "PEN"), 10));
      }
      Event event =
          Event.create(
              UUID.randomUUID(),
              new UUID(1, 1),
              "Event " + i,
              "",
              "Venue",
              NOW.plus(Duration.ofDays(10 + i)),
              NOW.plus(Duration.ofDays(11 + i)),
              zones,
              CLOCK);
      event.publish(CLOCK);
      repository.save(event);
      ids.add(event.id());
    }
    return ids;
  }

  @Test
  void should_load_all_zones_in_a_single_statement_when_an_event_is_read() {
    UUID id = saveEvents(3).getFirst();
    statistics.clear();

    Event loaded = repository.findById(id).orElseThrow();

    assertThat(loaded.zones()).hasSize(ZONES_PER_EVENT);
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void should_use_one_select_and_one_count_when_a_page_of_events_is_listed() {
    saveEvents(25);
    statistics.clear();

    PageResult<EventSummary> page =
        repository.search(
            EventSearchCriteria.visibleTo(Viewer.anonymous()),
            EventSort.STARTS_AT_ASC,
            new PageRequest(0, 25));

    assertThat(page.content()).hasSize(25);
    assertThat(page.totalElements()).isEqualTo(25);
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
  }

  @Test
  void should_not_touch_the_zone_table_when_listing_events() {
    saveEvents(10);
    statistics.clear();

    repository.search(
        EventSearchCriteria.visibleTo(Viewer.anonymous()),
        EventSort.TITLE_ASC,
        new PageRequest(0, 10));

    assertThat(statistics.getCollectionLoadCount()).isZero();
    assertThat(statistics.getEntityLoadCount()).isZero();
  }

  @Test
  void should_skip_the_select_when_the_requested_page_is_beyond_the_last_one() {
    saveEvents(3);
    statistics.clear();

    PageResult<EventSummary> page =
        repository.search(
            EventSearchCriteria.visibleTo(Viewer.anonymous()),
            EventSort.STARTS_AT_ASC,
            new PageRequest(5, 10));

    assertThat(page.content()).isEmpty();
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
  }
}
