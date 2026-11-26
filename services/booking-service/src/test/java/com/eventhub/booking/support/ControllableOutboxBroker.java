package com.eventhub.booking.support;

import com.eventhub.booking.infrastructure.adapter.out.messaging.BrokerPublishException;
import com.eventhub.booking.infrastructure.adapter.out.messaging.OutboxBroker;
import com.eventhub.booking.infrastructure.adapter.out.messaging.OutboxMessage;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The real broker adapter, with switches to make chosen messages fail as if RabbitMQ had not
 * confirmed them, and a hook that runs just before each send (to hold a relay in the middle of its
 * pass). It lets a test fail one specific row (or all of them) deterministically and instantly,
 * which pausing the container cannot do. Without a switch it passes everything through.
 */
public final class ControllableOutboxBroker implements OutboxBroker {

  private final OutboxBroker delegate;
  private volatile Predicate<OutboxMessage> failing = message -> false;
  private volatile Consumer<OutboxMessage> beforePublish = message -> {};

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

  /** Runs {@code hook} before every send; it may block the calling relay. */
  public void beforePublish(Consumer<OutboxMessage> hook) {
    this.beforePublish = hook;
  }

  public void reset() {
    this.failing = message -> false;
    this.beforePublish = message -> {};
  }

  @Override
  public void publish(OutboxMessage message) {
    beforePublish.accept(message);
    if (failing.test(message)) {
      throw new BrokerPublishException("Simulated broker failure for " + message.id());
    }
    delegate.publish(message);
  }
}
