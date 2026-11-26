package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a block in one transaction and gives the application a single failure type for losing a
 * race. Inside the block the repositories already raise {@link ConcurrencyConflictException} (they
 * flush before returning); a failure that only shows when the transaction commits (a deferred
 * constraint, a serialization failure) would otherwise escape as a different exception, so it is
 * translated here to the same one.
 */
@Component
class SpringTransactionRunner implements TransactionRunner {

  private final TransactionTemplate template;

  SpringTransactionRunner(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
  }

  @Override
  public <T> T execute(Supplier<T> work) {
    try {
      return template.execute(status -> work.get());
    } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
      throw new ConcurrencyConflictException("Transaction lost a race: " + e.getMessage(), e);
    } catch (TransactionSystemException e) {
      if (Conflicts.isConcurrencyFailure(e)) {
        throw new ConcurrencyConflictException("Commit lost a race: " + e.getMessage(), e);
      }
      throw e;
    }
  }
}
