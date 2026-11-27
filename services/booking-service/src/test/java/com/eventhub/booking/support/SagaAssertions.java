package com.eventhub.booking.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.port.out.FakePaymentGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The guarantee of the payment saga, read from the database and the gateway after a race: every
 * booking ends in exactly one of two coherent states. Either it is CONFIRMED with its payment
 * CAPTURED, its tickets held, one {@code BookingConfirmed} carrying that payment and no refund; or
 * it is EXPIRED/CANCELLED (or still PENDING) and whatever was charged was given back: payment
 * REFUNDED, one {@code BookingPaymentRefunded}, no {@code BookingConfirmed}. Money never stays
 * captured against a booking that is not confirmed, and tickets are never counted twice.
 */
public final class SagaAssertions {

  private SagaAssertions() {}

  /**
   * @param reconciled true once the reconciler has had its say: then no refund may remain pending
   *     and no charge may remain INITIATED. Before that a REFUND_PENDING is a legal resting place.
   */
  public static void assertConsistent(RealApp app, FakePaymentGateway gateway, boolean reconciled) {
    List<Map<String, Object>> bookings =
        app.jdbc.queryForList("SELECT id, status FROM booking ORDER BY id");
    for (Map<String, Object> row : bookings) {
      UUID id = (UUID) row.get("id");
      assertBooking(app, gateway, id, (String) row.get("status"), reconciled);
    }
    app.assertInventoryInvariant();
  }

  private static void assertBooking(
      RealApp app, FakePaymentGateway gateway, UUID id, String status, boolean reconciled) {
    List<Map<String, Object>> payments =
        app.jdbc.queryForList(
            "SELECT id, status FROM payment WHERE booking_id = ? ORDER BY created_at, id", id);
    long confirmedEvents = events(app, "BookingConfirmed", id);
    long refundedEvents = events(app, "BookingPaymentRefunded", id);
    long charged = payments.stream().filter(p -> wasCharged(gateway, p)).count();
    long refundedAtGateway =
        payments.stream().filter(p -> gateway.wasRefunded((UUID) p.get("id"))).count();
    String what = "booking " + id + " (" + status + ", payments " + statuses(payments) + ")";

    assertThat(charged).as("at most one effective charge: " + what).isLessThanOrEqualTo(1);
    assertThat(refundedAtGateway)
        .as("at most one effective refund: " + what)
        .isLessThanOrEqualTo(1);

    if (status.equals("CONFIRMED")) {
      assertConfirmed(app, gateway, id, payments, confirmedEvents, refundedEvents, what);
      return;
    }
    assertThat(confirmedEvents).as("no BookingConfirmed: " + what).isZero();
    assertThat(payments)
        .as("never a captured payment on a booking that is not confirmed: " + what)
        .noneMatch(p -> p.get("status").equals("CAPTURED"));
    assertThat(payments)
        .as("a charge that moved money has been given back (or is being): " + what)
        .allSatisfy(p -> assertNotHolding(gateway, p, reconciled, what));
    long refunded = payments.stream().filter(p -> p.get("status").equals("REFUNDED")).count();
    assertThat(refundedEvents)
        .as("one BookingPaymentRefunded per refund: " + what)
        .isEqualTo(refunded);
    if (reconciled) {
      assertThat(payments)
          .as("the reconciler left nothing pending: " + what)
          .noneMatch(
              p -> p.get("status").equals("INITIATED") || p.get("status").equals("REFUND_PENDING"));
    }
  }

  private static void assertConfirmed(
      RealApp app,
      FakePaymentGateway gateway,
      UUID id,
      List<Map<String, Object>> payments,
      long confirmedEvents,
      long refundedEvents,
      String what) {
    List<Map<String, Object>> captured =
        payments.stream().filter(p -> p.get("status").equals("CAPTURED")).toList();
    assertThat(captured).as("exactly one captured payment: " + what).hasSize(1);
    UUID paymentId = (UUID) captured.get(0).get("id");
    assertThat(gateway.holds(paymentId)).as("the gateway holds the money: " + what).isTrue();
    assertThat(confirmedEvents).as("one BookingConfirmed: " + what).isEqualTo(1);
    String carriesPayment =
        "SELECT count(*) FROM outbox_event WHERE event_type = 'BookingConfirmed'"
            + " AND aggregate_id = '"
            + id
            + "' AND payload::text LIKE '%"
            + paymentId
            + "%'";
    assertThat(app.count(carriesPayment))
        .as("the BookingConfirmed carries the captured payment: " + what)
        .isEqualTo(1);
    assertThat(refundedEvents).as("no refund on a confirmed booking: " + what).isZero();
    assertThat(payments)
        .as("no other live payment: " + what)
        .noneMatch(p -> !p.get("id").equals(paymentId) && wasCharged(gateway, p));
  }

  private static void assertNotHolding(
      FakePaymentGateway gateway, Map<String, Object> payment, boolean reconciled, String what) {
    UUID paymentId = (UUID) payment.get("id");
    String status = (String) payment.get("status");
    if (status.equals("REFUNDED")) {
      assertThat(gateway.holds(paymentId)).as("refunded means paid back: " + what).isFalse();
    } else if (gateway.holds(paymentId)) {
      assertThat(status)
          .as("money is held, so a refund must be on its way: " + what)
          .isIn("REFUND_PENDING", "INITIATED");
      assertThat(reconciled).as("the reconciler must have settled it: " + what).isFalse();
    }
  }

  private static boolean wasCharged(FakePaymentGateway gateway, Map<String, Object> payment) {
    UUID paymentId = (UUID) payment.get("id");
    return gateway.holds(paymentId) || gateway.wasRefunded(paymentId);
  }

  private static long events(RealApp app, String type, UUID bookingId) {
    return app.count(
        "SELECT count(*) FROM outbox_event WHERE event_type = '"
            + type
            + "' AND aggregate_id = '"
            + bookingId
            + "'");
  }

  private static String statuses(List<Map<String, Object>> payments) {
    return payments.stream().map(p -> (String) p.get("status")).toList().toString();
  }
}
