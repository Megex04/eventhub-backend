package com.eventhub.booking.infrastructure.adapter.in.web;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.port.in.EventAvailability;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingItem;
import com.eventhub.booking.domain.Money;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingDetailDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingItemDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingPageDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingStatusDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingSummaryDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.CancelReasonDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.CreateBookingRequestDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.EventAvailabilityDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.MoneyDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.PaymentDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.PaymentStatusDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.ZoneAvailabilityDto;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/** Explicit translation between the generated DTOs and the application and domain types. */
final class BookingWebMapper {

  private BookingWebMapper() {}

  static List<RequestedItem> toRequestedItems(CreateBookingRequestDto request) {
    return request.getItems().stream()
        .map(item -> new RequestedItem(item.getZoneId(), item.getQuantity()))
        .toList();
  }

  static BookingDetailDto toDetail(Booking booking) {
    BookingDetailDto dto =
        new BookingDetailDto(
            booking.id(),
            booking.eventId(),
            BookingStatusDto.valueOf(booking.status().name()),
            booking.items().stream().map(BookingWebMapper::toItem).toList(),
            toMoney(booking.total()),
            booking.createdAt(),
            booking.expiresAt());
    dto.setConfirmedAt(booking.confirmedAt().orElse(null));
    dto.setExpiredAt(booking.expiredAt().orElse(null));
    dto.setCancelledAt(booking.cancelledAt().orElse(null));
    dto.setCancelReason(
        booking.cancelReason().map(reason -> CancelReasonDto.valueOf(reason.name())).orElse(null));
    dto.setPayment(booking.payment().flatMap(BookingWebMapper::visiblePayment).orElse(null));
    return dto;
  }

  static BookingPageDto toPage(Page<Booking> page) {
    return new BookingPageDto(
        page.content().stream().map(BookingWebMapper::toSummary).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages());
  }

  static EventAvailabilityDto toAvailability(EventAvailability availability) {
    return new EventAvailabilityDto(
        availability.eventId(),
        availability.onSale(),
        availability.startsAt(),
        availability.zones().stream()
            .map(zone ->
                new ZoneAvailabilityDto(
                    zone.zoneId(),
                    zone.name(),
                    toMoney(zone.price()),
                    zone.capacity(),
                    zone.available()))
            .toList());
  }

  /** Amounts travel as strings with exactly two decimals, never as numbers. */
  static MoneyDto toMoney(Money money) {
    return new MoneyDto(
        money.amount().setScale(2, RoundingMode.HALF_EVEN).toPlainString(), money.currency());
  }

  private static BookingSummaryDto toSummary(Booking booking) {
    return new BookingSummaryDto(
        booking.id(),
        booking.eventId(),
        BookingStatusDto.valueOf(booking.status().name()),
        toMoney(booking.total()),
        booking.createdAt(),
        booking.expiresAt());
  }

  private static BookingItemDto toItem(BookingItem item) {
    return new BookingItemDto(
        item.zoneId(),
        item.zoneName(),
        item.quantity(),
        toMoney(item.unitPrice()),
        toMoney(item.subtotal()));
  }

  /**
   * INITIATED and DECLINED are internal to the payment saga: the API shows a payment only once
   * money has really moved (CAPTURED, REFUND_PENDING, REFUNDED).
   */
  private static Optional<PaymentDto> visiblePayment(Payment payment) {
    return switch (payment.status()) {
      case CAPTURED, REFUND_PENDING, REFUNDED ->
          Optional.of(
              new PaymentDto(
                  payment.id(),
                  PaymentStatusDto.valueOf(payment.status().name()),
                  toMoney(payment.amount())));
      case INITIATED, DECLINED -> Optional.empty();
    };
  }
}
