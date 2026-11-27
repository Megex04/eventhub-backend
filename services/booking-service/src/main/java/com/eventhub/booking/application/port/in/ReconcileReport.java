package com.eventhub.booking.application.port.in;

public record ReconcileReport(
    int refundsCompleted,
    int refundsFailed,
    int orphansConfirmed,
    int orphansRefunded,
    int orphansDeclined,
    int orphansUnresolved) {}
