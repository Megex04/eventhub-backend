package com.eventhub.booking.infrastructure.adapter.out.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV4IdGeneratorTest {

  private final UuidV4IdGenerator generator = new UuidV4IdGenerator();

  @Test
  void should_generate_version_four_uuids() {
    assertThat(generator.newId().version()).isEqualTo(4);
  }

  @Test
  void should_not_repeat_ids() {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      ids.add(generator.newId());
    }
    assertThat(ids).hasSize(1_000);
  }
}
