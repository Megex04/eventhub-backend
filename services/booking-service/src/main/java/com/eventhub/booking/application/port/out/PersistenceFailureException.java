package com.eventhub.booking.application.port.out;

/**
 * The database failed in a way that is not a lost race: an unreachable server, a rejected write, a
 * failed commit. A use case that already committed its main change (a cancellation, a captured
 * payment) treats it as "try again later" and leaves the rest to the reconciler; one that has not
 * committed anything lets it end the request.
 */
public final class PersistenceFailureException extends RuntimeException {

  public PersistenceFailureException(String message, Throwable cause) {
    super(message, cause);
  }
}
