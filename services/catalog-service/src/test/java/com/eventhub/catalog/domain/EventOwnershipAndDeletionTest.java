package com.eventhub.catalog.domain;

import static com.eventhub.catalog.domain.EventFixtures.ORGANIZER_ID;
import static com.eventhub.catalog.domain.EventFixtures.cancelledEvent;
import static com.eventhub.catalog.domain.EventFixtures.draftEvent;
import static com.eventhub.catalog.domain.EventFixtures.publishedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventOwnershipAndDeletionTest {

  @Test
  void should_be_organized_by_user_when_ids_match() {
    assertThat(draftEvent().isOrganizedBy(ORGANIZER_ID)).isTrue();
  }

  @Test
  void should_not_be_organized_by_user_when_ids_differ() {
    assertThat(draftEvent().isOrganizedBy(UUID.randomUUID())).isFalse();
  }

  @Test
  void should_not_be_organized_by_anyone_when_user_id_is_null() {
    assertThat(draftEvent().isOrganizedBy(null)).isFalse();
  }

  @Test
  void should_allow_deletion_when_event_is_draft() {
    assertThatCode(() -> draftEvent().ensureDeletable()).doesNotThrowAnyException();
  }

  @Test
  void should_reject_deletion_when_event_is_published() {
    assertThatThrownBy(() -> publishedEvent().ensureDeletable())
        .isInstanceOfSatisfying(
            InvalidStateException.class,
            e -> assertThat(e.currentStatus()).isEqualTo(EventStatus.PUBLISHED));
  }

  @Test
  void should_reject_deletion_when_event_is_cancelled() {
    assertThatThrownBy(() -> cancelledEvent().ensureDeletable())
        .isInstanceOf(InvalidStateException.class);
  }
}
