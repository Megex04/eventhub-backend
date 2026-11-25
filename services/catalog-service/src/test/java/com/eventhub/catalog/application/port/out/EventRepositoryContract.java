package com.eventhub.catalog.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.Money;
import com.eventhub.catalog.domain.TicketZone;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link EventRepository} must have. The in-memory fake and the JPA adapter run
 * this same suite, so the fake used by the service tests cannot drift from the real thing.
 *
 * <p>Timestamps are compared at microsecond precision because PostgreSQL stores no finer.
 */
public abstract class EventRepositoryContract {

  protected static final Instant NOW = Instant.parse("2026-06-01T12:00:00.123456Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  protected static final UUID ORGANIZER_A = new UUID(1, 1);
  protected static final UUID ORGANIZER_B = new UUID(1, 2);
  protected static final UUID ORGANIZER_C = new UUID(1, 3);
  private static final UUID OUTSIDER = new UUID(1, 99);

  private static final PageRequest FIRST_PAGE = new PageRequest(0, 100);

  /** The repository under test, backed by an empty store. */
  protected abstract EventRepository repository();

  /** Empties the store; runs before every test. */
  protected abstract void resetStorage();

  @BeforeEach
  void resetBeforeEach() {
    resetStorage();
  }

  // --- single-aggregate behaviour -------------------------------------------------------------

  @Test
  void should_return_empty_when_event_does_not_exist() {
    assertThat(repository().findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void should_round_trip_every_field_when_a_draft_with_zones_is_saved() {
    Event event =
        Event.create(
            UUID.randomUUID(),
            ORGANIZER_A,
            "Rock Fest",
            "Open air festival",
            "Estadio Nacional",
            days(30),
            days(30).plus(Duration.ofHours(4)),
            List.of(zone("General", "100.00", 500), zone("VIP", "250.50", 50)),
            CLOCK);

    repository().save(event);
    Event loaded = reload(event);

    assertThat(loaded.id()).isEqualTo(event.id());
    assertThat(loaded.organizerId()).isEqualTo(ORGANIZER_A);
    assertThat(loaded.title()).isEqualTo("Rock Fest");
    assertThat(loaded.description()).isEqualTo("Open air festival");
    assertThat(loaded.venue()).isEqualTo("Estadio Nacional");
    assertThat(loaded.startsAt()).isEqualTo(micros(days(30)));
    assertThat(loaded.endsAt()).isEqualTo(micros(days(30).plus(Duration.ofHours(4))));
    assertThat(loaded.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(loaded.cancelReason()).isEmpty();
    assertThat(loaded.createdAt()).isEqualTo(micros(NOW));
    assertThat(loaded.updatedAt()).isEqualTo(micros(NOW));
    assertThat(loaded.version()).isZero();
    assertThat(loaded.zones()).hasSize(2);
    assertThat(loaded.zones().get(0).id()).isEqualTo(event.zones().get(0).id());
    assertThat(loaded.zones().get(0).name()).isEqualTo("General");
    assertThat(loaded.zones().get(0).price()).isEqualTo(Money.of("100.00", "PEN"));
    assertThat(loaded.zones().get(0).capacity()).isEqualTo(500);
    assertThat(loaded.zones().get(1).name()).isEqualTo("VIP");
    assertThat(loaded.zones().get(1).price()).isEqualTo(Money.of("250.50", "PEN"));
  }

  @Test
  void should_store_empty_description_when_event_has_none() {
    Event event = draft(ORGANIZER_A, "No description", "Venue", days(10), "General");
    repository().save(event);

    assertThat(reload(event).description()).isEmpty();
  }

  @Test
  void should_round_trip_status_and_cancel_reason_when_event_is_cancelled() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "General"));
    Event loaded = reload(event);
    loaded.publish(CLOCK);
    loaded.cancel("Venue unavailable", CLOCK);
    repository().save(loaded);

    Event cancelled = reload(event);

    assertThat(cancelled.status()).isEqualTo(EventStatus.CANCELLED);
    assertThat(cancelled.cancelReason()).contains("Venue unavailable");
  }

  @Test
  void should_increment_version_when_each_save_succeeds() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "General"));
    assertThat(reload(event).version()).isZero();

    Event first = reload(event);
    first.update("Rock Fest II", "", "Venue", days(10), days(11), CLOCK);
    repository().save(first);
    assertThat(reload(event).version()).isEqualTo(1);

    Event second = reload(event);
    second.publish(CLOCK);
    repository().save(second);
    assertThat(reload(event).version()).isEqualTo(2);
  }

  @Test
  void should_persist_zone_changes_in_domain_order_when_zones_are_added_updated_and_removed() {
    Event event =
        saved(
            Event.create(
                UUID.randomUUID(),
                ORGANIZER_A,
                "Rock Fest",
                "",
                "Venue",
                days(10),
                days(11),
                List.of(zone("A", "10.00", 1), zone("B", "20.00", 2), zone("C", "30.00", 3)),
                CLOCK));

    Event loaded = reload(event);
    loaded.removeZone(loaded.zones().get(1).id(), CLOCK);
    loaded.addZone(zone("D", "40.00", 4), CLOCK);
    loaded.updateZone(loaded.zones().get(0).id(), "A renamed", Money.of("11.00", "PEN"), 10, CLOCK);
    repository().save(loaded);

    Event reloaded = reload(event);
    assertThat(reloaded.zones())
        .extracting(TicketZone::name)
        .containsExactly("A renamed", "C", "D");
    assertThat(reloaded.zones().get(0).capacity()).isEqualTo(10);
    assertThat(reloaded.zones().get(0).price()).isEqualTo(Money.of("11.00", "PEN"));
    assertThat(reloaded.zones().get(0).id()).isEqualTo(event.zones().get(0).id());
  }

  @Test
  void should_save_when_a_removed_zone_name_is_added_again_in_the_same_save() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "VIP"));
    Event loaded = reload(event);
    loaded.removeZone(loaded.zones().getFirst().id(), CLOCK);
    loaded.addZone(zone("vip", "99.00", 7), CLOCK);

    assertThatCode(() -> repository().save(loaded)).doesNotThrowAnyException();

    assertThat(reload(event).zones()).extracting(TicketZone::name).containsExactly("vip");
  }

  @Test
  void should_reject_a_stale_save_when_the_event_changed_after_it_was_loaded() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "General"));
    Event winner = reload(event);
    Event loser = reload(event);
    winner.update("Winner title", "", "Venue", days(10), days(11), CLOCK);
    loser.update("Loser title", "", "Venue", days(10), days(11), CLOCK);
    repository().save(winner);

    assertThatThrownBy(() -> repository().save(loser))
        .isInstanceOfSatisfying(
            EventVersionConflictException.class,
            e -> assertThat(e.eventId()).isEqualTo(event.id()));

    assertThat(reload(event).title()).isEqualTo("Winner title");
  }

  @Test
  void should_accept_a_save_when_the_event_was_reloaded_after_the_other_change() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "General"));
    Event first = reload(event);
    first.update("First", "", "Venue", days(10), days(11), CLOCK);
    repository().save(first);

    Event second = reload(event);
    second.update("Second", "", "Venue", days(10), days(11), CLOCK);
    repository().save(second);

    assertThat(reload(event).title()).isEqualTo("Second");
  }

  @Test
  void should_delete_the_event_with_its_zones_when_versions_match() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "General", "VIP"));

    repository().delete(reload(event));

    assertThat(repository().findById(event.id())).isEmpty();
  }

  @Test
  void should_keep_the_event_when_deleting_with_a_stale_version() {
    Event event = saved(draft(ORGANIZER_A, "Rock Fest", "Venue", days(10), "General"));
    Event stale = reload(event);
    Event current = reload(event);
    current.publish(CLOCK);
    repository().save(current);

    assertThatThrownBy(() -> repository().delete(stale))
        .isInstanceOf(EventVersionConflictException.class);

    assertThat(reload(event).status()).isEqualTo(EventStatus.PUBLISHED);
  }

  @Test
  void should_ignore_delete_when_the_event_does_not_exist() {
    Event neverSaved = draft(ORGANIZER_A, "Ghost", "Venue", days(10), "General");

    assertThatCode(() -> repository().delete(neverSaved)).doesNotThrowAnyException();
  }

  // --- search: visibility, filters, ordering, paging ------------------------------------------

  @Test
  void should_return_summaries_with_the_stored_values_when_searching() {
    Event event = saved(published(ORGANIZER_A, "Rock Fest", "Estadio", days(10)));

    PageResult<EventSummary> result = search(Viewer.anonymous(), EventSort.STARTS_AT_ASC);

    assertThat(result.content())
        .containsExactly(
            new EventSummary(
                event.id(),
                "Rock Fest",
                "Estadio",
                micros(days(10)),
                micros(days(10).plus(Duration.ofHours(2))),
                EventStatus.PUBLISHED));
  }

  @Test
  void should_return_an_empty_page_with_zero_totals_when_nothing_matches() {
    PageResult<EventSummary> result = search(Viewer.anonymous(), EventSort.STARTS_AT_ASC);

    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isZero();
    assertThat(result.totalPages()).isZero();
  }

  @Test
  void should_show_only_published_events_when_viewer_is_anonymous() {
    Catalog catalog = seedCatalog();

    PageResult<EventSummary> result = search(Viewer.anonymous(), EventSort.STARTS_AT_ASC);

    assertThat(result.totalElements()).isEqualTo(catalog.published().size());
    assertThat(result.content())
        .extracting(EventSummary::status)
        .containsOnly(EventStatus.PUBLISHED);
  }

  @Test
  void should_show_published_events_of_everyone_when_viewer_has_no_events() {
    Catalog catalog = seedCatalog();

    PageResult<EventSummary> result =
        search(Viewer.authenticated(OUTSIDER), EventSort.STARTS_AT_ASC);

    assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(catalog.published());
  }

  @Test
  void should_add_the_viewers_own_unpublished_events_when_viewer_is_an_organizer() {
    Catalog catalog = seedCatalog();

    PageResult<EventSummary> result =
        search(Viewer.authenticated(ORGANIZER_A), EventSort.STARTS_AT_ASC);

    List<UUID> expected = new ArrayList<>(catalog.published());
    expected.addAll(catalog.unpublishedOf(ORGANIZER_A));
    assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(ids(result)).doesNotContainAnyElementsOf(catalog.unpublishedOf(ORGANIZER_B));
  }

  @Test
  void should_return_only_own_events_in_any_status_when_mine_is_requested() {
    Catalog catalog = seedCatalog();

    PageResult<EventSummary> result =
        repository()
            .search(
                EventSearchCriteria.visibleTo(Viewer.authenticated(ORGANIZER_A)).mineOnly(),
                EventSort.STARTS_AT_ASC,
                FIRST_PAGE);

    assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(catalog.allOf(ORGANIZER_A));
  }

  @Test
  void should_combine_mine_status_and_text_filters_when_all_are_given() {
    seedCatalog();
    Event draft = saved(draft(ORGANIZER_A, "Zeppelin tribute", "Hall", days(500), "General"));
    saved(draft(ORGANIZER_A, "Other draft", "Hall", days(501), "General"));

    PageResult<EventSummary> result =
        repository()
            .search(
                EventSearchCriteria.visibleTo(Viewer.authenticated(ORGANIZER_A))
                    .mineOnly()
                    .withStatus(EventStatus.DRAFT)
                    .withText("zeppelin"),
                EventSort.STARTS_AT_ASC,
                FIRST_PAGE);

    assertThat(ids(result)).containsExactly(draft.id());
  }

  @Test
  void should_show_only_the_viewers_own_events_when_filtering_by_a_hidden_status() {
    Catalog catalog = seedCatalog();

    PageResult<EventSummary> others =
        repository()
            .search(
                EventSearchCriteria.visibleTo(Viewer.authenticated(ORGANIZER_A))
                    .withStatus(EventStatus.DRAFT),
                EventSort.STARTS_AT_ASC,
                FIRST_PAGE);
    PageResult<EventSummary> anonymous =
        repository()
            .search(
                EventSearchCriteria.visibleTo(Viewer.anonymous()).withStatus(EventStatus.DRAFT),
                EventSort.STARTS_AT_ASC,
                FIRST_PAGE);

    assertThat(ids(others)).containsExactlyInAnyOrderElementsOf(catalog.draftsOf(ORGANIZER_A));
    assertThat(anonymous.totalElements()).isZero();
  }

  @Test
  void should_page_without_gaps_or_duplicates_when_events_of_several_organizers_are_listed() {
    Catalog catalog = seedCatalog();
    int total = catalog.published().size();
    List<UUID> collected = new ArrayList<>();

    PageResult<EventSummary> page0 = page(Viewer.anonymous(), 0, 5);
    PageResult<EventSummary> page1 = page(Viewer.anonymous(), 1, 5);
    PageResult<EventSummary> page2 = page(Viewer.anonymous(), 2, 5);
    for (PageResult<EventSummary> page : List.of(page0, page1, page2)) {
      collected.addAll(ids(page));
    }

    assertThat(page0.content()).hasSize(5);
    assertThat(page1.content()).hasSize(5);
    assertThat(page2.content()).hasSize(total - 10);
    assertThat(page0.totalElements()).isEqualTo(total);
    assertThat(page0.totalPages()).isEqualTo(3);
    assertThat(new HashSet<>(collected)).hasSize(total);
    assertThat(collected).containsExactlyElementsOf(catalog.publishedByStartAscending());
    assertThat(page(Viewer.anonymous(), 3, 5).content()).isEmpty();
    assertThat(page(Viewer.anonymous(), 3, 5).totalElements()).isEqualTo(total);
  }

  @Test
  void should_page_the_visible_events_when_an_organizer_view_mixes_own_and_foreign_events() {
    Catalog catalog = seedCatalog();
    int expected = catalog.published().size() + catalog.unpublishedOf(ORGANIZER_A).size();
    List<UUID> collected = new ArrayList<>();

    int pages = 0;
    PageResult<EventSummary> page;
    do {
      page = page(Viewer.authenticated(ORGANIZER_A), pages, 7);
      collected.addAll(ids(page));
      pages++;
    } while (pages < page.totalPages());

    assertThat(page.totalElements()).isEqualTo(expected);
    assertThat(collected).hasSize(expected);
    assertThat(new HashSet<>(collected)).hasSize(expected);
  }

  @Test
  void should_order_by_start_date_in_both_directions_when_sort_is_by_starts_at() {
    Catalog catalog = seedCatalog();

    assertThat(ids(search(Viewer.anonymous(), EventSort.STARTS_AT_ASC)))
        .containsExactlyElementsOf(catalog.publishedByStartAscending());
    assertThat(ids(search(Viewer.anonymous(), EventSort.STARTS_AT_DESC)))
        .containsExactlyElementsOf(catalog.publishedByStartAscending().reversed());
  }

  @Test
  void should_order_by_title_in_both_directions_when_sort_is_by_title() {
    Event bravo = saved(published(ORGANIZER_A, "Bravo night", "Venue", days(20)));
    Event alpha = saved(published(ORGANIZER_B, "Alpha night", "Venue", days(21)));
    Event charlie = saved(published(ORGANIZER_C, "Charlie night", "Venue", days(19)));

    assertThat(ids(search(Viewer.anonymous(), EventSort.TITLE_ASC)))
        .containsExactly(alpha.id(), bravo.id(), charlie.id());
    assertThat(ids(search(Viewer.anonymous(), EventSort.TITLE_DESC)))
        .containsExactly(charlie.id(), bravo.id(), alpha.id());
  }

  @Test
  void should_break_ties_by_id_so_paging_is_stable_when_dates_are_equal() {
    Instant sameStart = days(15);
    Event first =
        savedWithId(new UUID(0, 1), published(ORGANIZER_A, "Twin one", "Venue", sameStart));
    Event second =
        savedWithId(new UUID(0, 2), published(ORGANIZER_B, "Twin two", "Venue", sameStart));

    assertThat(ids(page(Viewer.anonymous(), 0, 1))).containsExactly(first.id());
    assertThat(ids(page(Viewer.anonymous(), 1, 1))).containsExactly(second.id());
  }

  @Test
  void should_filter_by_start_date_inclusively_when_from_and_to_are_given() {
    Event early = saved(published(ORGANIZER_A, "Early", "Venue", days(10)));
    Event middle = saved(published(ORGANIZER_A, "Middle", "Venue", days(20)));
    Event late = saved(published(ORGANIZER_A, "Late", "Venue", days(30)));

    EventSearchCriteria between =
        EventSearchCriteria.visibleTo(Viewer.anonymous()).withFrom(days(20)).withTo(days(30));
    EventSearchCriteria fromOnly =
        EventSearchCriteria.visibleTo(Viewer.anonymous()).withFrom(days(21));
    EventSearchCriteria toOnly = EventSearchCriteria.visibleTo(Viewer.anonymous()).withTo(days(19));

    assertThat(ids(repository().search(between, EventSort.STARTS_AT_ASC, FIRST_PAGE)))
        .containsExactly(middle.id(), late.id());
    assertThat(ids(repository().search(fromOnly, EventSort.STARTS_AT_ASC, FIRST_PAGE)))
        .containsExactly(late.id());
    assertThat(ids(repository().search(toOnly, EventSort.STARTS_AT_ASC, FIRST_PAGE)))
        .containsExactly(early.id());
  }

  @Test
  void should_match_title_or_venue_ignoring_case_when_text_is_given() {
    Event byTitle = saved(published(ORGANIZER_A, "Rock Fest", "Hall", days(10)));
    Event byVenue = saved(published(ORGANIZER_A, "Gala", "Teatro Colon", days(11)));
    saved(published(ORGANIZER_A, "Opera", "Hall", days(12)));

    assertThat(ids(text("rock"))).containsExactly(byTitle.id());
    assertThat(ids(text("COLON"))).containsExactly(byVenue.id());
  }

  @Test
  void
      should_treat_wildcards_as_literal_characters_when_text_has_percent_underscore_or_backslash() {
    Event percent = saved(published(ORGANIZER_A, "Sale 50% off", "Hall", days(10)));
    saved(published(ORGANIZER_A, "Sale 500 off", "Hall", days(11)));
    Event underscore = saved(published(ORGANIZER_A, "Jazz_Night", "Hall", days(12)));
    saved(published(ORGANIZER_A, "JazzXNight", "Hall", days(13)));
    Event backslash = saved(published(ORGANIZER_A, "Back\\slash", "Hall", days(14)));

    assertThat(ids(text("50%"))).containsExactly(percent.id());
    assertThat(ids(text("_"))).containsExactly(underscore.id());
    assertThat(ids(text("\\"))).containsExactly(backslash.id());
    assertThat(ids(text("%"))).containsExactly(percent.id());
  }

  // --- helpers --------------------------------------------------------------------------------

  // --- replayable events (ADR 0003) ------------------------------------------------------------

  /** Small positive ids sort the same in Java (signed) and in PostgreSQL (unsigned bytes). */
  private static UUID replayId(int n) {
    return new UUID(1, 100 + n);
  }

  private Event cancelledWithId(UUID id) {
    Event event =
        reload(savedWithId(id, draft(ORGANIZER_A, "Cancelled", "Venue", days(10), "General")));
    event.cancel("Venue unavailable", CLOCK);
    repository().save(event);
    return reload(event);
  }

  @Test
  void should_list_only_published_and_cancelled_events_when_replayable_events_are_requested() {
    saved(draft(ORGANIZER_A, "Draft", "Venue", days(10), "General"));
    savedWithId(replayId(1), draft(ORGANIZER_A, "Published", "Venue", days(11), "General"));
    cancelledWithId(replayId(2));

    List<Event> replayable = repository().findReplayable(Optional.empty(), 10);

    assertThat(replayable).extracting(Event::id).containsExactly(replayId(1), replayId(2));
    assertThat(replayable)
        .extracting(Event::status)
        .containsExactly(EventStatus.PUBLISHED, EventStatus.CANCELLED);
  }

  @Test
  void should_load_the_zones_and_the_version_when_replayable_events_are_requested() {
    savedWithId(replayId(1), draft(ORGANIZER_A, "Two zones", "Venue", days(11), "General", "VIP"));

    Event event = repository().findReplayable(Optional.empty(), 10).getFirst();

    assertThat(event.zones()).extracting(TicketZone::name).containsExactly("General", "VIP");
    assertThat(event.version()).isEqualTo(reload(event).version());
  }

  @Test
  void should_order_by_id_and_honour_the_limit_when_replayable_events_are_requested() {
    for (int n = 5; n >= 1; n--) {
      savedWithId(replayId(n), draft(ORGANIZER_A, "Event " + n, "Venue", days(10 + n), "General"));
    }

    assertThat(repository().findReplayable(Optional.empty(), 3))
        .extracting(Event::id)
        .containsExactly(replayId(1), replayId(2), replayId(3));
  }

  @Test
  void should_continue_after_the_cursor_when_after_is_given() {
    for (int n = 1; n <= 5; n++) {
      savedWithId(replayId(n), draft(ORGANIZER_A, "Event " + n, "Venue", days(10 + n), "General"));
    }

    assertThat(repository().findReplayable(Optional.of(replayId(3)), 10))
        .extracting(Event::id)
        .containsExactly(replayId(4), replayId(5));
    assertThat(repository().findReplayable(Optional.of(replayId(5)), 10)).isEmpty();
  }

  @Test
  void should_return_nothing_when_every_event_is_a_draft() {
    saved(draft(ORGANIZER_A, "Draft", "Venue", days(10), "General"));

    assertThat(repository().findReplayable(Optional.empty(), 10)).isEmpty();
  }

  private PageResult<EventSummary> search(Viewer viewer, EventSort sort) {
    return repository().search(EventSearchCriteria.visibleTo(viewer), sort, FIRST_PAGE);
  }

  private PageResult<EventSummary> page(Viewer viewer, int page, int size) {
    return repository()
        .search(
            EventSearchCriteria.visibleTo(viewer),
            EventSort.STARTS_AT_ASC,
            new PageRequest(page, size));
  }

  private PageResult<EventSummary> text(String text) {
    return repository()
        .search(
            EventSearchCriteria.visibleTo(Viewer.anonymous()).withText(text),
            EventSort.STARTS_AT_ASC,
            FIRST_PAGE);
  }

  private static List<UUID> ids(PageResult<EventSummary> result) {
    return result.content().stream().map(EventSummary::id).toList();
  }

  private Event reload(Event event) {
    return repository().findById(event.id()).orElseThrow();
  }

  private Event saved(Event event) {
    repository().save(event);
    return event;
  }

  private Event savedWithId(UUID id, Event template) {
    Event event =
        Event.create(
            id,
            template.organizerId(),
            template.title(),
            template.description(),
            template.venue(),
            template.startsAt(),
            template.endsAt(),
            template.zones(),
            CLOCK);
    event.publish(CLOCK);
    return saved(event);
  }

  private static Instant days(int days) {
    return NOW.plus(Duration.ofDays(days));
  }

  private static Instant micros(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }

  private static TicketZone zone(String name, String price, int capacity) {
    return TicketZone.create(UUID.randomUUID(), name, Money.of(price, "PEN"), capacity);
  }

  private static Event draft(
      UUID organizer, String title, String venue, Instant startsAt, String... zoneNames) {
    List<TicketZone> zones = new ArrayList<>();
    for (String name : zoneNames) {
      zones.add(zone(name, "100.00", 100));
    }
    return Event.create(
        UUID.randomUUID(),
        organizer,
        title,
        "",
        venue,
        startsAt,
        startsAt.plus(Duration.ofHours(2)),
        zones,
        CLOCK);
  }

  private static Event published(UUID organizer, String title, String venue, Instant startsAt) {
    Event event = draft(organizer, title, venue, startsAt, "General");
    event.publish(CLOCK);
    return event;
  }

  /**
   * Three organizers, eight events each: 4 published, 2 drafts, 2 cancelled; unique start dates.
   */
  private Catalog seedCatalog() {
    Catalog catalog = new Catalog();
    UUID[] organizers = {ORGANIZER_A, ORGANIZER_B, ORGANIZER_C};
    String[] names = {"A", "B", "C"};
    int day = 1;
    for (int round = 1; round <= 8; round++) {
      for (int o = 0; o < organizers.length; o++) {
        String title = "Event " + names[o] + "-" + String.format("%02d", round);
        Instant startsAt = days(day++);
        Event event = draft(organizers[o], title, "Venue " + names[o], startsAt, "General");
        EventStatus target =
            round <= 4
                ? EventStatus.PUBLISHED
                : round <= 6 ? EventStatus.DRAFT : EventStatus.CANCELLED;
        if (target != EventStatus.DRAFT) {
          event.publish(CLOCK);
        }
        if (target == EventStatus.CANCELLED) {
          event.cancel("Called off", CLOCK);
        }
        saved(event);
        catalog.add(organizers[o], target, event);
      }
    }
    return catalog;
  }

  private static final class Catalog {
    private final List<Event> published = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    private record Entry(UUID organizer, EventStatus status, UUID id) {}

    void add(UUID organizer, EventStatus status, Event event) {
      entries.add(new Entry(organizer, status, event.id()));
      if (status == EventStatus.PUBLISHED) {
        published.add(event);
      }
    }

    List<UUID> published() {
      return published.stream().map(Event::id).toList();
    }

    List<UUID> publishedByStartAscending() {
      return published.stream()
          .sorted(java.util.Comparator.comparing(Event::startsAt))
          .map(Event::id)
          .toList();
    }

    List<UUID> allOf(UUID organizer) {
      return entries.stream().filter(e -> e.organizer().equals(organizer)).map(Entry::id).toList();
    }

    List<UUID> draftsOf(UUID organizer) {
      return entries.stream()
          .filter(e -> e.organizer().equals(organizer) && e.status() == EventStatus.DRAFT)
          .map(Entry::id)
          .toList();
    }

    List<UUID> unpublishedOf(UUID organizer) {
      return entries.stream()
          .filter(e -> e.organizer().equals(organizer) && e.status() != EventStatus.PUBLISHED)
          .map(Entry::id)
          .toList();
    }
  }
}
