package com.eventhub.catalog.domain;

/** A business rule is not met although the request is well-formed (maps to HTTP 422). */
public final class BusinessRuleViolationException extends DomainException {

  /** Stable identifiers so the web adapter can expose a distinct problem type per rule. */
  public enum Rule {
    EVENT_WITHOUT_ZONES,
    STARTS_AT_NOT_IN_FUTURE,
    EVENT_ALREADY_STARTED,
    ZONE_LIMIT_EXCEEDED,
    DUPLICATE_ZONE_NAME
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
