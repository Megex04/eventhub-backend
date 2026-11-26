package com.eventhub.booking.infrastructure.adapter.in.web;

import static com.eventhub.booking.application.AppFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.CancelReason;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingDetailDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingStatusDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.PaymentStatusDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BookingWebMapperTest {

  private static Booking pending() {
    return newBooking(uuid(1), USER, at(NOW), new RequestedItem(VIP_ZONE, 2));
  }

  @ParameterizedTest
  @CsvSource({"5,5.00", "5.5,5.50", "150.00,150.00", "0.1,0.10", "9999999999.99,9999999999.99"})
  void should_write_money_as_a_string_with_two_decimals(String amount, String expected) {
    var dto = BookingWebMapper.toMoney(new Money(new BigDecimal(amount), "PEN"));

    assertThat(dto.getAmount()).isEqualTo(expected);
    assertThat(dto.getCurrency()).isEqualTo("PEN");
  }

  @Test
  void should_map_a_pending_booking_with_its_priced_items() {
    BookingDetailDto dto = BookingWebMapper.toDetail(pending());

    assertThat(dto.getStatus()).isEqualTo(BookingStatusDto.PENDING);
    assertThat(dto.getTotal().getAmount()).isEqualTo("300.00");
    assertThat(dto.getItems()).hasSize(1);
    assertThat(dto.getItems().get(0).getZoneName()).isEqualTo("VIP");
    assertThat(dto.getItems().get(0).getUnitPrice().getAmount()).isEqualTo("150.00");
    assertThat(dto.getItems().get(0).getSubtotal().getAmount()).isEqualTo("300.00");
    assertThat(dto.getExpiresAt()).isEqualTo(NOW.plus(HOLD));
    assertThat(dto.getConfirmedAt()).isNull();
    assertThat(dto.getPayment()).isNull();
  }

  @Test
  void should_hide_a_payment_that_is_only_initiated() {
    Booking booking = pending();
    booking.beginPayment(uuid(7), "tok_approved", at(NOW));

    assertThat(BookingWebMapper.toDetail(booking).getPayment()).isNull();
  }

  @Test
  void should_hide_a_payment_that_was_declined() {
    Booking booking = pending();
    booking.beginPayment(uuid(7), "tok_declined", at(NOW));
    booking.declinePayment();

    assertThat(BookingWebMapper.toDetail(booking).getPayment()).isNull();
  }

  @Test
  void should_show_the_payment_once_money_moved() {
    Booking booking = pending();
    booking.beginPayment(uuid(7), "tok_approved", at(NOW));
    booking.capturePayment(at(NOW.plusSeconds(1)));

    BookingDetailDto dto = BookingWebMapper.toDetail(booking);

    assertThat(dto.getStatus()).isEqualTo(BookingStatusDto.CONFIRMED);
    assertThat(dto.getPayment().getStatus()).isEqualTo(PaymentStatusDto.CAPTURED);
    assertThat(dto.getPayment().getAmount().getAmount()).isEqualTo("300.00");
    assertThat(dto.getConfirmedAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void should_show_the_refund_and_the_reason_of_a_cancelled_booking() {
    Booking booking = pending();
    booking.beginPayment(uuid(7), "tok_approved", at(NOW));
    booking.capturePayment(at(NOW.plusSeconds(1)));
    booking.cancel(CancelReason.EVENT_CANCELLED, EVENT_STARTS, at(NOW.plusSeconds(2)));

    BookingDetailDto dto = BookingWebMapper.toDetail(booking);

    assertThat(dto.getStatus()).isEqualTo(BookingStatusDto.CANCELLED);
    assertThat(dto.getCancelReason().getValue()).isEqualTo("EVENT_CANCELLED");
    assertThat(dto.getPayment().getStatus()).isEqualTo(PaymentStatusDto.REFUND_PENDING);
  }

  @Test
  void should_map_a_page_with_its_totals() {
    var dto = BookingWebMapper.toPage(new Page<>(List.of(pending()), 1, 1, 3));

    assertThat(dto.getContent()).hasSize(1);
    assertThat(dto.getPage()).isEqualTo(1);
    assertThat(dto.getSize()).isEqualTo(1);
    assertThat(dto.getTotalElements()).isEqualTo(3L);
    assertThat(dto.getTotalPages()).isEqualTo(3);
  }
}
