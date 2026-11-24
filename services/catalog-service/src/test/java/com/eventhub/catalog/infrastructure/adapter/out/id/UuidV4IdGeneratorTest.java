package com.eventhub.catalog.infrastructure.adapter.out.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UuidV4IdGeneratorTest {

  private final UuidV4IdGenerator generator = new UuidV4IdGenerator();

  @Test
  void should_generate_version_4_uuids_when_asked_for_an_id() {
    assertThat(generator.newId().version()).isEqualTo(4);
  }

  @Test
  void should_generate_distinct_ids_when_called_repeatedly() {
    assertThat(Stream.generate(generator::newId).limit(1000).map(UUID::toString))
        .doesNotHaveDuplicates();
  }
}
