package com.eventhub.booking.domain;

/**
 * The operation is not allowed in the current state of a booking or payment (maps to HTTP 409
 * {@code invalid-state}).
 */
public final class InvalidStateException extends DomainException {

  private final String currentStatus;

  private InvalidStateException(String message, String currentStatus) {
    super(message);
    this.currentStatus = currentStatus;
  }

  static InvalidStateException notAllowed(String subject, String action, Enum<?> currentStatus) {
    return new InvalidStateException(
        "Cannot %s a %s in status %s".formatted(action, subject, currentStatus),
        currentStatus.name());
  }

  /** The hold is still running: a booking only expires once {@code now >= expiresAt}. */
  static InvalidStateException holdStillActive(BookingStatus currentStatus) {
    return new InvalidStateException(
        "Cannot expire a booking before its hold ends", currentStatus.name());
  }

  /** The hold ended at {@code expiresAt}: the booking can no longer be paid. */
  static InvalidStateException holdEnded(BookingStatus currentStatus) {
    return new InvalidStateException(
        "Cannot pay a booking whose hold has ended", currentStatus.name());
  }

  static InvalidStateException noPayment(BookingStatus currentStatus) {
    return new InvalidStateException("The booking has no payment attempt", currentStatus.name());
  }

  public String currentStatus() {
    return currentStatus;
  }
}
