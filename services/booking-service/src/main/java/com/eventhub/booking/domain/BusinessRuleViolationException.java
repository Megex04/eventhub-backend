package com.eventhub.booking.domain;

/** A business rule is not met although the request is well-formed (maps to HTTP 422). */
public final class BusinessRuleViolationException extends DomainException {

  /** Stable identifiers so the web adapter can expose a distinct problem type per rule. */
  public enum Rule {
    MAX_TICKETS_EXCEEDED,
    DUPLICATE_ZONE,
    EVENT_NOT_ON_SALE,
    ZONE_NOT_IN_EVENT,
    MIXED_CURRENCY,
    CANCEL_AFTER_START
  }

  private final Rule rule;

  BusinessRuleViolationException(Rule rule, String message) {
    super(message);
    this.rule = rule;
  }

  public Rule rule() {
    return rule;
  }
}
