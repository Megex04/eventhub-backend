package com.eventhub.booking.infrastructure.adapter.out.messaging;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.application.port.in.ReconcileReport;
import com.eventhub.booking.application.port.out.FakePaymentGateway.Gate;
import com.eventhub.booking.infrastructure.adapter.in.web.ApiIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A booking and its outbox row are written in one transaction: whatever makes either write (or the
 * commit) fail leaves neither behind, in a plain use case and in every step of the payment saga.
 * The failures are real database errors raised by triggers created for the test, so the whole
 * stack, down to the commit, is what is being checked.
 */
class OutboxAtomicityIT extends ApiIT {

  private static final String FAILING_FUNCTION =
      """
      CREATE OR REPLACE FUNCTION it_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        RAISE EXCEPTION 'simulated outbox failure';
      END
      $$
      """;

  @Autowired private ReconcileRefundsUseCase reconcile;
  @Autowired private MeterRegistry meters;

  @AfterEach
  void dropTriggers() {
    app.jdbc.execute("DROP TRIGGER IF EXISTS it_fail_on_insert ON outbox_event");
    app.jdbc.execute("DROP TRIGGER IF EXISTS it_fail_at_commit ON outbox_event");
    app.jdbc.execute("DROP FUNCTION IF EXISTS it_fail_outbox()");
  }

  /** Every outbox insert of {@code eventType} (all types when null) fails right away. */
  private void failImmediately(String eventType) {
    app.jdbc.execute(FAILING_FUNCTION);
    app.jdbc.execute(
        "CREATE TRIGGER it_fail_on_insert BEFORE INSERT ON outbox_event FOR EACH ROW"
            + condition(eventType)
            + " EXECUTE FUNCTION it_fail_outbox()");
  }

  /** Both writes succeed; the transaction fails only when it tries to commit. */
  private void failAtCommit(String eventType) {
    app.jdbc.execute(FAILING_FUNCTION);
    app.jdbc.execute(
        "CREATE CONSTRAINT TRIGGER it_fail_at_commit AFTER INSERT ON outbox_event"
            + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
            + condition(eventType)
            + " EXECUTE FUNCTION it_fail_outbox()");
  }

  private static String condition(String eventType) {
    return eventType == null ? "" : " WHEN (NEW.event_type = '" + eventType + "')";
  }

  private void expectInternalError(ResultActions result) throws Exception {
    result
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM))
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "internal-error"))
        .andExpect(jsonPath("$.detail").value("The request could not be processed"));
  }

  private List<String> outboxTypes() {
    return app.jdbc.queryForList(
        "SELECT event_type FROM outbox_event ORDER BY created_at, id", String.class);
  }

  private double immediateRefundFailures() {
    return meters.counter("payments.refund.immediate.failed").count();
  }

  private String paymentStatus(UUID bookingId) {
    return app.jdbc.queryForObject(
        "SELECT status FROM payment WHERE booking_id = ?", String.class, bookingId);
  }

  private String bookingStatus(UUID bookingId) {
    return app.jdbc.queryForObject(
        "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
  }

  // ---------------------------------------------------------------- create

  @Test
  void should_store_no_booking_no_tickets_and_no_row_when_the_outbox_write_fails()
      throws Exception {
    failImmediately(null);

    expectInternalError(createVip(alice, UUID.randomUUID(), 2));

    assertThat(app.count("SELECT count(*) FROM booking")).isZero();
    assertThat(app.count("SELECT count(*) FROM outbox_event")).isZero();
    assertThat(app.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_store_no_booking_no_tickets_and_no_row_when_the_commit_fails() throws Exception {
    failAtCommit(null);

    expectInternalError(createVip(alice, UUID.randomUUID(), 2));

    assertThat(app.count("SELECT count(*) FROM booking")).isZero();
    assertThat(app.count("SELECT count(*) FROM outbox_event")).isZero();
    assertThat(app.reserved(VIP_ZONE)).isZero();
  }

  @Test
  void should_store_no_row_and_hold_no_tickets_when_the_failure_comes_after_the_booking_was_saved()
      throws Exception {
    // The booking is inserted first and its tickets reserved second; the outbox row is written
    // last.
    // A sold-out zone fails after the booking was saved, and must leave no booking and no row.
    app.tx.run(() ->
        app.inventory.reserve(
            EVENT, List.of(new com.eventhub.booking.domain.RequestedItem(VIP_ZONE, 20))));

    createVip(alice, UUID.randomUUID(), 1).andExpect(status().isConflict());

    assertThat(app.count("SELECT count(*) FROM booking")).isZero();
    assertThat(app.count("SELECT count(*) FROM outbox_event")).isZero();
  }

  @Test
  void should_create_normally_once_the_failure_is_gone() throws Exception {
    failImmediately(null);
    expectInternalError(createVip(alice, uuid(500), 2));
    dropTriggers();

    createVip(alice, uuid(500), 2).andExpect(status().isCreated());

    assertThat(outboxTypes()).containsExactly("BookingCreated");
  }

  // ---------------------------------------------------------------- pay: step 3

  @Test
  void should_leave_the_payment_initiated_and_write_no_confirmation_when_the_outbox_write_fails()
      throws Exception {
    UUID id = booked(alice, 2);
    failImmediately("BookingConfirmed");

    expectInternalError(pay(alice, id, "tok_approved"));

    assertThat(bookingStatus(id)).as("the booking is not confirmed").isEqualTo("PENDING");
    assertThat(paymentStatus(id)).as("the charge keeps its trace").isEqualTo("INITIATED");
    assertThat(outboxTypes())
        .as("no confirmation without the state")
        .containsExactly("BookingCreated");
    assertThat(gateway.distinctCharges()).as("the money did move").isEqualTo(1);
    fetch(alice, id).andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void should_keep_refusing_a_second_payment_while_the_first_is_unresolved() throws Exception {
    UUID id = booked(alice, 2);
    failImmediately("BookingConfirmed");
    expectInternalError(pay(alice, id, "tok_approved"));

    pay(alice, id, "tok_approved")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(TYPE_BASE + "payment-in-progress"));

    assertThat(gateway.distinctCharges()).isEqualTo(1);
  }

  @Test
  void should_let_the_reconciler_confirm_the_booking_once_the_outbox_works_again()
      throws Exception {
    UUID id = booked(alice, 2);
    failImmediately("BookingConfirmed");
    expectInternalError(pay(alice, id, "tok_approved"));
    dropTriggers();
    clock.advance(Duration.ofMinutes(6));

    ReconcileReport report = reconcile.reconcile();

    assertThat(report.orphansConfirmed()).isEqualTo(1);
    assertThat(bookingStatus(id)).isEqualTo("CONFIRMED");
    assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
    assertThat(outboxTypes()).containsExactly("BookingCreated", "BookingConfirmed");
    assertThat(gateway.distinctCharges())
        .as("repeating the charge never charges twice")
        .isEqualTo(1);
    fetch(alice, id)
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.payment.status").value("CAPTURED"));
  }

  @Test
  void should_leave_the_payment_initiated_and_write_no_confirmation_when_the_commit_fails()
      throws Exception {
    UUID id = booked(alice, 2);
    failAtCommit("BookingConfirmed");

    expectInternalError(pay(alice, id, "tok_approved"));

    assertThat(bookingStatus(id)).isEqualTo("PENDING");
    assertThat(paymentStatus(id)).isEqualTo("INITIATED");
    assertThat(outboxTypes()).containsExactly("BookingCreated");
  }

  // ---------------------------------------------------------------- refund completion

  @Test
  void should_leave_the_refund_pending_and_write_no_refund_event_when_the_outbox_write_fails()
      throws Exception {
    UUID id = booked(alice, 1);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    failImmediately("BookingPaymentRefunded");

    double failedBefore = immediateRefundFailures();

    // The cancellation itself is committed; completing the refund is what fails. The caller gets
    // the committed state, not an error: the refund is best effort and the reconciler finishes it.
    cancel(alice, id)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.payment.status").value("REFUND_PENDING"));

    assertThat(immediateRefundFailures() - failedBefore).isEqualTo(1);
    assertThat(bookingStatus(id)).isEqualTo("CANCELLED");
    assertThat(paymentStatus(id)).isEqualTo("REFUND_PENDING");
    assertThat(outboxTypes())
        .containsExactly("BookingCreated", "BookingConfirmed", "BookingCancelled");
    assertThat(app.reserved(VIP_ZONE))
        .as("the tickets were released with the cancellation")
        .isZero();
  }

  @Test
  void should_let_the_reconciler_complete_the_refund_once_the_outbox_works_again()
      throws Exception {
    UUID id = booked(alice, 1);
    pay(alice, id, "tok_approved").andExpect(status().isOk());
    failImmediately("BookingPaymentRefunded");
    cancel(alice, id).andExpect(status().isOk());
    dropTriggers();

    ReconcileReport report = reconcile.reconcile();

    assertThat(report.refundsCompleted()).isEqualTo(1);
    assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
    assertThat(outboxTypes())
        .containsExactly(
            "BookingCreated", "BookingConfirmed", "BookingCancelled", "BookingPaymentRefunded");
    assertThat(gateway.distinctRefunds()).as("refunded once, however many attempts").isEqualTo(1);
  }

  @Test
  void should_answer_refund_pending_in_pay_and_let_the_reconciler_finish_it() throws Exception {
    UUID id = booked(alice, 2);
    Gate gate = gateway.holdCharges();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    double failedBefore = immediateRefundFailures();
    try {
      Future<MockHttpServletResponse> paying =
          pool.submit(() -> pay(alice, id, "tok_approved").andReturn().getResponse());
      gate.awaitArrival();
      clock.set(EXPIRES_AT);
      failImmediately("BookingPaymentRefunded");
      gate.open();

      MockHttpServletResponse response = paying.get();
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(response.getContentAsString()).contains(TYPE_BASE + "payment-refund-pending");
    } finally {
      pool.shutdownNow();
    }

    assertThat(immediateRefundFailures() - failedBefore).isEqualTo(1);
    assertThat(bookingStatus(id)).isEqualTo("PENDING");
    assertThat(paymentStatus(id)).isEqualTo("REFUND_PENDING");
    assertThat(outboxTypes()).containsExactly("BookingCreated");
    dropTriggers();

    ReconcileReport report = reconcile.reconcile();

    assertThat(report.refundsCompleted()).isEqualTo(1);
    assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
    assertThat(outboxTypes()).containsExactly("BookingCreated", "BookingPaymentRefunded");
    assertThat(gateway.distinctRefunds()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- cancel and expire

  @Test
  void
      should_keep_the_booking_pending_and_the_tickets_held_when_the_cancellation_cannot_be_recorded()
          throws Exception {
    UUID id = booked(alice, 2);
    failImmediately("BookingCancelled");

    expectInternalError(cancel(alice, id));

    assertThat(bookingStatus(id)).isEqualTo("PENDING");
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(2);
    assertThat(outboxTypes()).containsExactly("BookingCreated");
  }

  @Test
  void should_expire_nothing_and_keep_the_tickets_when_the_expiration_cannot_be_recorded()
      throws Exception {
    UUID id = booked(alice, 2);
    clock.advance(HOLD);
    failImmediately("BookingExpired");

    assertThatThrownBy(() -> expire.expireDue(10)).isInstanceOf(RuntimeException.class);

    assertThat(bookingStatus(id)).isEqualTo("PENDING");
    assertThat(app.reserved(VIP_ZONE)).isEqualTo(2);
    assertThat(outboxTypes()).containsExactly("BookingCreated");

    dropTriggers();
    assertThat(expire.expireDue(10)).isEqualTo(1);
    assertThat(bookingStatus(id)).isEqualTo("EXPIRED");
    assertThat(outboxTypes()).containsExactly("BookingCreated", "BookingExpired");
    assertThat(app.reserved(VIP_ZONE)).isZero();
  }
}
