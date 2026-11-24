package com.eventhub.catalog.application.service;

import static com.eventhub.catalog.application.service.Fixture.OWNER;
import static com.eventhub.catalog.application.service.Fixture.STARTS_AT;
import static com.eventhub.catalog.application.service.Fixture.STRANGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.catalog.application.exception.AuthenticationRequiredException;
import com.eventhub.catalog.application.exception.EventNotFoundException;
import com.eventhub.catalog.application.port.in.ListEventsUseCase;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.Event;
import com.eventhub.catalog.domain.EventStatus;
import com.eventhub.catalog.domain.InvalidValueException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventQueryServiceTest {

  private final Fixture fixture = new Fixture();
  private final Viewer anonymous = Viewer.anonymous();
  private final Viewer owner = Viewer.authenticated(OWNER);
  private final Viewer stranger = Viewer.authenticated(STRANGER);

  @Nested
  class Get {

    @Test
    void should_return_published_event_when_viewer_is_anonymous() {
      Event stored = fixture.givenPublished(OWNER);

      assertThat(fixture.queries.get(stored.id(), anonymous).id()).isEqualTo(stored.id());
    }

    @Test
    void should_return_published_event_when_viewer_is_another_user() {
      Event stored = fixture.givenPublished(OWNER);

      assertThat(fixture.queries.get(stored.id(), stranger).id()).isEqualTo(stored.id());
    }

    @Test
    void should_hide_draft_when_viewer_is_anonymous() {
      Event stored = fixture.givenDraft(OWNER);

      assertThatThrownBy(() -> fixture.queries.get(stored.id(), anonymous))
          .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void should_hide_draft_when_viewer_is_another_user() {
      Event stored = fixture.givenDraft(OWNER);

      assertThatThrownBy(() -> fixture.queries.get(stored.id(), stranger))
          .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void should_hide_cancelled_event_when_viewer_is_not_the_organizer() {
      Event stored = fixture.givenCancelled(OWNER);

      assertThatThrownBy(() -> fixture.queries.get(stored.id(), stranger))
          .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void should_return_draft_and_cancelled_events_when_viewer_is_the_organizer() {
      Event draft = fixture.givenDraft(OWNER);
      Event cancelled = fixture.givenCancelled(OWNER);

      assertThat(fixture.queries.get(draft.id(), owner).id()).isEqualTo(draft.id());
      assertThat(fixture.queries.get(cancelled.id(), owner).id()).isEqualTo(cancelled.id());
    }

    @Test
    void should_report_not_found_when_event_does_not_exist() {
      UUID unknown = UUID.randomUUID();

      assertThatThrownBy(() -> fixture.queries.get(unknown, owner))
          .isInstanceOfSatisfying(
              EventNotFoundException.class, e -> assertThat(e.eventId()).isEqualTo(unknown));
    }
  }

  @Nested
  class List_ {

    private ListEventsUseCase.Query query(EventSearchCriteria criteria) {
      return new ListEventsUseCase.Query(criteria, EventSort.STARTS_AT_ASC, new PageRequest(0, 20));
    }

    @Test
    void should_return_the_repository_page_when_listing() {
      Event published = fixture.givenPublished(OWNER);
      fixture.givenDraft(OWNER);

      PageResult<EventSummary> result =
          fixture.queries.list(query(EventSearchCriteria.visibleTo(anonymous)));

      assertThat(result.content()).extracting(EventSummary::id).containsExactly(published.id());
      assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void should_pass_viewer_filters_sort_and_page_to_the_repository() {
      Instant from = STARTS_AT;
      Instant to = STARTS_AT.plusSeconds(3600);
      EventSearchCriteria criteria =
          EventSearchCriteria.visibleTo(owner)
              .withStatus(EventStatus.DRAFT)
              .withText("rock")
              .withFrom(from)
              .withTo(to)
              .mineOnly();

      fixture.queries.list(
          new ListEventsUseCase.Query(criteria, EventSort.TITLE_DESC, new PageRequest(2, 50)));

      assertThat(fixture.repository.lastCriteria()).isEqualTo(criteria);
      assertThat(fixture.repository.lastCriteria().status()).contains(EventStatus.DRAFT);
      assertThat(fixture.repository.lastCriteria().text()).contains("rock");
      assertThat(fixture.repository.lastCriteria().from()).contains(from);
      assertThat(fixture.repository.lastCriteria().to()).contains(to);
      assertThat(fixture.repository.lastCriteria().onlyMine()).isTrue();
      assertThat(fixture.repository.lastSort()).isEqualTo(EventSort.TITLE_DESC);
      assertThat(fixture.repository.lastPage()).isEqualTo(new PageRequest(2, 50));
    }

    @Test
    void should_require_authentication_when_mine_is_requested_anonymously() {
      EventSearchCriteria criteria = EventSearchCriteria.visibleTo(anonymous).mineOnly();

      assertThatThrownBy(() -> fixture.queries.list(query(criteria)))
          .isInstanceOf(AuthenticationRequiredException.class);
      assertThat(fixture.repository.searchCount()).isZero();
    }

    @Test
    void should_allow_mine_when_viewer_is_authenticated() {
      fixture.queries.list(query(EventSearchCriteria.visibleTo(stranger).mineOnly()));

      assertThat(fixture.repository.searchCount()).isEqualTo(1);
    }

    @Test
    void should_not_restrict_to_one_organizer_when_mine_is_not_requested() {
      fixture.queries.list(query(EventSearchCriteria.visibleTo(owner)));

      assertThat(fixture.repository.lastCriteria().onlyMine()).isFalse();
      assertThat(fixture.repository.lastCriteria().status()).isEmpty();
    }

    @Test
    void should_normalize_blank_text_filter_to_no_filter_when_text_is_blank() {
      assertThat(EventSearchCriteria.visibleTo(anonymous).withText("   ").text()).isEmpty();
    }

    @Test
    void should_strip_text_filter_when_it_has_surrounding_spaces() {
      assertThat(EventSearchCriteria.visibleTo(anonymous).withText("  rock ").text())
          .contains("rock");
    }

    @Test
    void should_reject_text_filter_when_it_exceeds_one_hundred_characters() {
      assertThat(EventSearchCriteria.visibleTo(anonymous).withText("a".repeat(100)).text())
          .isPresent();
      assertThatThrownBy(() -> EventSearchCriteria.visibleTo(anonymous).withText("a".repeat(101)))
          .isInstanceOfSatisfying(
              InvalidValueException.class, e -> assertThat(e.field()).isEqualTo("q"));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101", "0,-5"})
    void should_reject_page_request_when_values_are_out_of_range(int page, int size) {
      assertThatThrownBy(() -> new PageRequest(page, size))
          .isInstanceOf(InvalidValueException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "0,100", "7,20"})
    void should_accept_page_request_when_values_are_in_range(int page, int size) {
      assertThat(new PageRequest(page, size).size()).isEqualTo(size);
    }

    @Test
    void should_compute_total_pages_when_result_has_partial_last_page() {
      assertThat(new PageResult<>(List.of(), 0, 20, 41).totalPages()).isEqualTo(3);
      assertThat(new PageResult<>(List.of(), 0, 20, 40).totalPages()).isEqualTo(2);
      assertThat(new PageResult<>(List.of(), 0, 20, 0).totalPages()).isZero();
    }

    @Test
    void should_report_optional_filters_as_empty_when_none_is_set() {
      EventSearchCriteria criteria = EventSearchCriteria.visibleTo(anonymous);

      assertThat(criteria.status()).isEqualTo(Optional.empty());
      assertThat(criteria.from()).isEmpty();
      assertThat(criteria.to()).isEmpty();
    }
  }

  @Test
  void should_identify_the_organizer_when_viewer_is_authenticated_as_owner() {
    Event stored = fixture.givenDraft(OWNER);

    assertThat(owner.isOrganizerOf(stored)).isTrue();
    assertThat(stranger.isOrganizerOf(stored)).isFalse();
    assertThat(anonymous.isOrganizerOf(stored)).isFalse();
    assertThat(anonymous.isAuthenticated()).isFalse();
    assertThat(owner.isAuthenticated()).isTrue();
  }
}
