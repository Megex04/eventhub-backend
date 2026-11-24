package com.eventhub.catalog.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

  private final Clock clock = new ClockConfig().clock();

  @Test
  void should_report_utc_when_zone_is_requested() {
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void should_never_expose_sub_microsecond_precision_when_instants_are_read() {
    assertThat(Stream.generate(clock::instant).limit(2000))
        .allSatisfy(instant -> assertThat(instant.getNano() % 1000).isZero());
  }

  @Test
  void should_not_go_backwards_when_instants_are_read_in_sequence() {
    assertThat(clock.instant()).isBeforeOrEqualTo(clock.instant());
  }
}
