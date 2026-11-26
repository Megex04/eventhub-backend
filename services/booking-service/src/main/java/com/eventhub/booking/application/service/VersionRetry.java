package com.eventhub.booking.application.service;

import com.eventhub.booking.application.port.out.BookingVersionConflictException;
import com.eventhub.booking.application.port.out.TransactionRunner;
import java.util.function.Supplier;

/**
 * Runs a short transaction again when the booking changed between reading and saving it. Each
 * attempt reloads the aggregate, so it decides on the current state: that is what turns a lost race
 * (for instance an expiry that landed during a charge) into the right outcome instead of an error.
 */
final class VersionRetry {

  private static final int MAX_ATTEMPTS = 3;

  private VersionRetry() {}

  static <T> T run(TransactionRunner transactions, Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(work);
      } catch (BookingVersionConflictException conflict) {
        if (attempt == MAX_ATTEMPTS) {
          throw conflict;
        }
      }
    }
  }
}
