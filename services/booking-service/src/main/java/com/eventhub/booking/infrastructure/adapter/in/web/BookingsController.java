package com.eventhub.booking.infrastructure.adapter.in.web;

import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.application.port.in.CancelBookingUseCase;
import com.eventhub.booking.application.port.in.CreateBookingCommand;
import com.eventhub.booking.application.port.in.CreateBookingResult;
import com.eventhub.booking.application.port.in.CreateBookingUseCase;
import com.eventhub.booking.application.port.in.GetBookingUseCase;
import com.eventhub.booking.application.port.in.ListBookingsQuery;
import com.eventhub.booking.application.port.in.ListMyBookingsUseCase;
import com.eventhub.booking.application.port.in.PayBookingCommand;
import com.eventhub.booking.application.port.in.PayBookingUseCase;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.InvalidValueException;
import com.eventhub.booking.infrastructure.adapter.in.web.api.BookingsApi;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingDetailDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingPageDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.BookingStatusDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.CreateBookingRequestDto;
import com.eventhub.booking.infrastructure.adapter.in.web.model.PayBookingRequestDto;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Translates HTTP to a command, delegates to the use case and translates the result back. It holds
 * no business rule: who the caller is comes from the token, everything else from the use cases.
 */
@RestController
class BookingsController implements BookingsApi {

  private static final String SORT_NEWEST_FIRST = "-createdAt";
  private static final String SORT_OLDEST_FIRST = "createdAt";

  private final CreateBookingUseCase createBooking;
  private final PayBookingUseCase payBooking;
  private final CancelBookingUseCase cancelBooking;
  private final GetBookingUseCase getBooking;
  private final ListMyBookingsUseCase listMyBookings;

  BookingsController(
      CreateBookingUseCase createBooking,
      PayBookingUseCase payBooking,
      CancelBookingUseCase cancelBooking,
      GetBookingUseCase getBooking,
      ListMyBookingsUseCase listMyBookings) {
    this.createBooking = createBooking;
    this.payBooking = payBooking;
    this.cancelBooking = cancelBooking;
    this.getBooking = getBooking;
    this.listMyBookings = listMyBookings;
  }

  @Override
  public ResponseEntity<BookingDetailDto> createBooking(
      UUID idempotencyKey, CreateBookingRequestDto request) {
    CreateBookingResult result =
        createBooking.create(
            new CreateBookingCommand(
                CurrentUser.requireId(),
                idempotencyKey,
                request.getEventId(),
                BookingWebMapper.toRequestedItems(request)));
    BookingDetailDto body = BookingWebMapper.toDetail(result.booking());
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/bookings/" + body.getId())).body(body);
  }

  @Override
  public ResponseEntity<BookingPageDto> listMyBookings(
      BookingStatusDto status, Integer page, Integer size, String sort) {
    Optional<BookingStatus> filter =
        Optional.ofNullable(status).map(s -> BookingStatus.valueOf(s.name()));
    var result =
        listMyBookings.list(
            new ListBookingsQuery(
                CurrentUser.requireId(), filter, new PageQuery(page, size, newestFirst(sort))));
    return ResponseEntity.ok(BookingWebMapper.toPage(result));
  }

  @Override
  public ResponseEntity<BookingDetailDto> getBooking(UUID bookingId) {
    return ResponseEntity.ok(
        BookingWebMapper.toDetail(getBooking.get(CurrentUser.requireId(), bookingId)));
  }

  @Override
  public ResponseEntity<BookingDetailDto> payBooking(UUID bookingId, PayBookingRequestDto request) {
    return ResponseEntity.ok(
        BookingWebMapper.toDetail(
            payBooking.pay(
                new PayBookingCommand(
                    CurrentUser.requireId(), bookingId, request.getPaymentToken()))));
  }

  @Override
  public ResponseEntity<BookingDetailDto> cancelBooking(UUID bookingId) {
    return ResponseEntity.ok(
        BookingWebMapper.toDetail(cancelBooking.cancel(CurrentUser.requireId(), bookingId)));
  }

  private static boolean newestFirst(String sort) {
    return switch (sort) {
      case SORT_NEWEST_FIRST -> true;
      case SORT_OLDEST_FIRST -> false;
      default -> throw new InvalidValueException("sort", "sort must be createdAt or -createdAt");
    };
  }
}
