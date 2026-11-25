package com.eventhub.booking.application.port.out;

public sealed interface ChargeResult {

  record Approved() implements ChargeResult {}

  record Declined(String reason) implements ChargeResult {}
}
