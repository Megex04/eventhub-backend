package com.eventhub.booking.infrastructure.adapter.out.persistence;

import java.sql.SQLException;

/** Reads the SQLSTATE and constraint name out of a persistence failure, whatever wraps it. */
final class Conflicts {

  private static final String UNIQUE_VIOLATION = "23505";

  private Conflicts() {}

  /** True if the failure is a unique violation that names {@code constraint}. */
  static boolean isUniqueViolation(Throwable failure, String constraint) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && UNIQUE_VIOLATION.equals(sql.getSQLState())
          && String.valueOf(sql.getMessage()).contains(constraint)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True if the failure is the database refusing a write because of concurrent work: an integrity
   * constraint (class 23), a serialization failure or deadlock (class 40) or a lock that could not
   * be taken (55P03).
   */
  static boolean isConcurrencyFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        String state = sql.getSQLState();
        if (state.startsWith("23") || state.startsWith("40") || state.equals("55P03")) {
          return true;
        }
      }
    }
    return false;
  }
}
