package com.eventhub.catalog.support;

import com.eventhub.catalog.infrastructure.adapter.out.messaging.BrokerPublishException;
import com.eventhub.catalog.infrastructure.adapter.out.messaging.OutboxBroker;
import com.eventhub.catalog.infrastructure.adapter.out.messaging.OutboxMessage;
import java.util.function.Predicate;

/**
 * The real broker adapter, with a switch to make chosen messages fail as if RabbitMQ had not
 * confirmed them. It lets a test fail one specific row (or all of them) deterministically and
 * instantly, which pausing the container cannot do. Without a switch it passes everything through.
 */
public final class ControllableOutboxBroker implements OutboxBroker {

  private final OutboxBroker delegate;
  private volatile Predicate<OutboxMessage> failing = message -> false;

  ControllableOutboxBroker(OutboxBroker delegate) {
    this.delegate = delegate;
  }

  /** Fails every message the predicate accepts until {@link #reset()}. */
  public void failWhen(Predicate<OutboxMessage> predicate) {
    this.failing = predicate;
  }

  public void failAlways() {
    failWhen(message -> true);
  }

  public void reset() {
    this.failing = message -> false;
  }

  @Override
  public void publish(OutboxMessage message) {
    if (failing.test(message)) {
      throw new BrokerPublishException("Simulated broker failure for " + message.id());
    }
    delegate.publish(message);
  }
}
