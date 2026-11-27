package com.eventhub.booking.application.port.out;

import static com.eventhub.booking.application.AppFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public abstract class ProcessedMessageRepositoryContract {

  protected abstract ProcessedMessageRepository repository();

  @Test
  void should_report_a_message_as_new_only_the_first_time_it_is_marked() {
    assertThat(repository().isProcessed(uuid(1))).isFalse();

    assertThat(repository().markProcessed(uuid(1))).isTrue();
    assertThat(repository().markProcessed(uuid(1))).isFalse();

    assertThat(repository().isProcessed(uuid(1))).isTrue();
    assertThat(repository().isProcessed(uuid(2))).isFalse();
  }
}
