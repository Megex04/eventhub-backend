package com.eventhub.booking.application.port.in;

public interface ReconcileRefundsUseCase {

  /** One pass: finishes pending refunds and resolves charges left INITIATED. */
  ReconcileReport reconcile();
}
