package com.eventhub.booking.application.port.in;

public interface CreateBookingUseCase {

  CreateBookingResult create(CreateBookingCommand command);
}
