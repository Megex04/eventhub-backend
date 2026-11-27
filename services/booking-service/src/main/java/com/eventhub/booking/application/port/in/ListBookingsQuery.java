package com.eventhub.booking.application.port.in;

import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.domain.BookingStatus;
import java.util.Optional;
import java.util.UUID;

public record ListBookingsQuery(UUID userId, Optional<BookingStatus> status, PageQuery page) {}
