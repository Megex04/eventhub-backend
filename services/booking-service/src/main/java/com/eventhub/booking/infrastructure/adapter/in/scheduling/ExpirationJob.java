package com.eventhub.booking.infrastructure.adapter.in.scheduling;

import com.eventhub.booking.application.port.in.ExpireBookingsUseCase;
import com.eventhub.booking.infrastructure.config.BookingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires the bookings whose hold ended, on a fixed delay. Off with {@code
 * eventhub.booking.expiration.enabled=false}; tests then call {@link #expireDue()} themselves. Safe
 * with several instances: the use case locks each booking with SKIP LOCKED.
 */
@Component
@ConditionalOnProperty(
    prefix = "eventhub.booking.expiration",
    name = "enabled",
    havingValue = "true")
public class ExpirationJob {

  private static final Logger log = LoggerFactory.getLogger(ExpirationJob.class);

  private final ExpireBookingsUseCase expiration;
  private final int batchSize;

  ExpirationJob(ExpireBookingsUseCase expiration, BookingProperties properties) {
    this.expiration = expiration;
    this.batchSize = properties.expiration().batchSize();
  }

  @Scheduled(fixedDelayString = "${eventhub.booking.expiration.poll-interval}")
  public void expireDue() {
    int expired = expiration.expireDue(batchSize);
    if (expired > 0) {
      log.info("Expired {} bookings", expired);
    }
  }
}
