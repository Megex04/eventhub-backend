package com.eventhub.booking.infrastructure.adapter.out.persistence;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventhub.booking.support.PersistenceIT;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database refuses what the domain would never do: the last line of defence. */
@PersistenceIT
class SchemaConstraintsIT {

  private static final Timestamp T0 = Timestamp.from(NOW);
  private static final Timestamp T1 = Timestamp.from(EXPIRES_AT);

  @Autowired private JdbcTemplate jdbc;

  private void zone(int capacity, int reserved) {
    jdbc.update(
        "INSERT INTO zone_inventory (event_id, zone_id, capacity, reserved) VALUES (?, ?, ?, ?)",
        EVENT,
        VIP_ZONE,
        capacity,
        reserved);
  }

  private void booking(
      UUID id, UUID userId, UUID key, String status, Timestamp cancelledAt, String reason) {
    jdbc.update(
        "INSERT INTO booking (id, user_id, event_id, status, total_amount, currency, created_at,"
            + " expires_at, cancelled_at, cancel_reason, expired_at, confirmed_at, idempotency_key,"
            + " request_fingerprint) VALUES (?, ?, ?, ?, 10.00, 'PEN', ?, ?, ?, ?, ?, ?, ?, 'fp')",
        id,
        userId,
        EVENT,
        status,
        T0,
        T1,
        cancelledAt,
        reason,
        status.equals("EXPIRED") ? T1 : null,
        status.equals("CONFIRMED") ? T1 : null,
        key);
  }

  private void payment(UUID id, UUID bookingId, String status, String token, String refundReason) {
    jdbc.update(
        "INSERT INTO payment (id, booking_id, status, amount, currency, payment_token,"
            + " refund_reason, created_at) VALUES (?, ?, ?, 10.00, 'PEN', ?, ?, ?)",
        id,
        bookingId,
        status,
        token,
        refundReason,
        T0);
  }

  // ---- inventory ----

  @Test
  void should_refuse_a_zone_reserved_beyond_its_capacity() {
    assertThatThrownBy(() -> zone(10, 11))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_zone_inventory_reserved");
  }

  @Test
  void should_refuse_a_negative_reserved_count() {
    assertThatThrownBy(() -> zone(10, -1))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_zone_inventory_reserved");
  }

  @Test
  void should_refuse_an_update_that_would_oversell() {
    zone(10, 10);

    assertThatThrownBy(() -> jdbc.update("UPDATE zone_inventory SET reserved = reserved + 1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_zone_inventory_reserved");
  }

  @Test
  void should_accept_a_full_zone() {
    assertThatCode(() -> zone(10, 10)).doesNotThrowAnyException();
  }

  // ---- bookings ----

  @Test
  void should_refuse_a_second_booking_with_the_same_user_and_idempotency_key() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);

    assertThatThrownBy(() -> booking(uuid(2), USER, uuid(900), "PENDING", null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_booking_user_idempotency");
  }

  @Test
  void should_accept_the_same_idempotency_key_for_another_user() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);

    assertThatCode(() -> booking(uuid(2), OTHER_USER, uuid(900), "PENDING", null, null))
        .doesNotThrowAnyException();
  }

  @Test
  void should_refuse_a_cancelled_booking_without_its_instant_and_reason() {
    assertThatThrownBy(() -> booking(uuid(1), USER, uuid(900), "CANCELLED", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void should_refuse_a_pending_booking_that_carries_a_cancellation() {
    assertThatThrownBy(() -> booking(uuid(1), USER, uuid(900), "PENDING", T1, "USER_REQUEST"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void should_refuse_an_unknown_status() {
    assertThatThrownBy(() -> booking(uuid(1), USER, uuid(900), "PAID", null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_booking_status");
  }

  // ---- payments ----

  @Test
  void should_refuse_two_live_payments_for_the_same_booking() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);
    payment(uuid(700), uuid(1), "INITIATED", "tok_a", null);

    assertThatThrownBy(() -> payment(uuid(701), uuid(1), "INITIATED", "tok_b", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_payment_active_per_booking");
  }

  @Test
  void should_refuse_a_new_attempt_while_a_captured_payment_is_still_live() {
    booking(uuid(1), USER, uuid(900), "CONFIRMED", null, null);
    payment(uuid(700), uuid(1), "CAPTURED", null, null);

    assertThatThrownBy(() -> payment(uuid(701), uuid(1), "INITIATED", "tok_b", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_payment_active_per_booking");
  }

  @Test
  void should_accept_a_new_attempt_after_a_declined_one() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);
    payment(uuid(700), uuid(1), "DECLINED", null, null);

    assertThatCode(() -> payment(uuid(701), uuid(1), "INITIATED", "tok_b", null))
        .doesNotThrowAnyException();
  }

  @Test
  void should_accept_a_new_attempt_after_a_refunded_one() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);
    payment(uuid(700), uuid(1), "REFUNDED", null, "BOOKING_CANCELLED");

    assertThatCode(() -> payment(uuid(701), uuid(1), "INITIATED", "tok_b", null))
        .doesNotThrowAnyException();
  }

  @Test
  void should_refuse_a_token_on_a_payment_that_is_no_longer_initiated() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);

    assertThatThrownBy(() -> payment(uuid(700), uuid(1), "CAPTURED", "tok_a", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_payment_token_only_while_initiated");
  }

  @Test
  void should_refuse_a_refund_reason_on_a_payment_that_is_not_being_refunded() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);

    assertThatThrownBy(() -> payment(uuid(700), uuid(1), "CAPTURED", null, "BOOKING_CANCELLED"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_payment_refund_reason");
  }

  @Test
  void should_demand_a_refund_reason_when_the_refund_is_pending() {
    booking(uuid(1), USER, uuid(900), "PENDING", null, null);

    assertThatThrownBy(() -> payment(uuid(700), uuid(1), "REFUND_PENDING", null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_payment_refund_reason");
  }

  // ---- projection ----

  @Test
  void should_refuse_an_open_event_without_a_schedule() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO event_projection (event_id, status, last_occurred_at)"
                    + " VALUES (?, 'OPEN', ?)",
                EVENT,
                T0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void should_accept_a_cancelled_tombstone_without_dates() {
    assertThatCode(() ->
            jdbc.update(
                "INSERT INTO event_projection (event_id, status, last_occurred_at)"
                    + " VALUES (?, 'CANCELLED', ?)",
                EVENT,
                T0))
        .doesNotThrowAnyException();
  }
}
