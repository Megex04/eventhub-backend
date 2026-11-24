package com.eventhub.catalog.infrastructure.adapter.out.id;

import com.eventhub.catalog.application.port.out.IdGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Random UUIDs (v4). Swap for UUIDv7 here if a library is adopted; nothing else changes. */
@Component
public class UuidV4IdGenerator implements IdGenerator {

  @Override
  public UUID newId() {
    return UUID.randomUUID();
  }
}
