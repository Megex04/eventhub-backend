package com.eventhub.booking.infrastructure.config;

import com.eventhub.booking.application.port.in.ApplyEventCancelledUseCase;
import com.eventhub.booking.application.port.in.ApplyEventPublishedUseCase;
import com.eventhub.booking.application.port.in.ApplyEventRescheduledUseCase;
import com.eventhub.booking.application.port.in.CancelBookingUseCase;
import com.eventhub.booking.application.port.in.CreateBookingUseCase;
import com.eventhub.booking.application.port.in.ExpireBookingsUseCase;
import com.eventhub.booking.application.port.in.GetAvailabilityUseCase;
import com.eventhub.booking.application.port.in.GetBookingUseCase;
import com.eventhub.booking.application.port.in.ListMyBookingsUseCase;
import com.eventhub.booking.application.port.in.PayBookingUseCase;
import com.eventhub.booking.application.port.in.ReconcileRefundsUseCase;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.DomainEventPublisher;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.application.port.out.IdGenerator;
import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.application.port.out.PaymentGateway;
import com.eventhub.booking.application.port.out.ProcessedMessageRepository;
import com.eventhub.booking.application.port.out.ReconciliationRepository;
import com.eventhub.booking.application.port.out.TransactionRunner;
import com.eventhub.booking.application.service.ApplyEventCancelledService;
import com.eventhub.booking.application.service.ApplyEventPublishedService;
import com.eventhub.booking.application.service.ApplyEventRescheduledService;
import com.eventhub.booking.application.service.CancelBookingService;
import com.eventhub.booking.application.service.CreateBookingService;
import com.eventhub.booking.application.service.ExpireBookingsService;
import com.eventhub.booking.application.service.GetAvailabilityService;
import com.eventhub.booking.application.service.GetBookingService;
import com.eventhub.booking.application.service.ListMyBookingsService;
import com.eventhub.booking.application.service.PayBookingService;
import com.eventhub.booking.application.service.ReconcileRefundsService;
import com.eventhub.booking.application.service.ReconcilerPolicy;
import com.eventhub.booking.domain.BookingPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases to their adapters. The application layer carries no Spring annotations, so
 * this is the one place where the services become beans.
 */
@Configuration
@EnableConfigurationProperties(BookingProperties.class)
class ApplicationConfig {

  @Bean
  BookingPolicy bookingPolicy(BookingProperties properties) {
    return new BookingPolicy(properties.holdDuration(), properties.maxTickets());
  }

  @Bean
  ReconcilerPolicy reconcilerPolicy(BookingProperties properties) {
    BookingProperties.Reconciler reconciler = properties.reconciler();
    return new ReconcilerPolicy(
        reconciler.initiatedStaleAfter(),
        reconciler.initialBackoff(),
        reconciler.maxBackoff(),
        reconciler.batchSize());
  }

  @Bean
  CreateBookingUseCase createBookingUseCase(
      TransactionRunner transactions,
      BookingRepository bookings,
      InventoryRepository inventory,
      EventProjectionRepository projections,
      DomainEventPublisher publisher,
      IdGenerator ids,
      Clock clock,
      BookingPolicy policy) {
    return new CreateBookingService(
        transactions, bookings, inventory, projections, publisher, ids, clock, policy);
  }

  @Bean
  PayBookingUseCase payBookingUseCase(
      TransactionRunner transactions,
      BookingRepository bookings,
      DomainEventPublisher publisher,
      IdGenerator ids,
      PaymentGateway gateway,
      Clock clock) {
    return new PayBookingService(transactions, bookings, publisher, ids, gateway, clock);
  }

  @Bean
  CancelBookingUseCase cancelBookingUseCase(
      TransactionRunner transactions,
      BookingRepository bookings,
      InventoryRepository inventory,
      EventProjectionRepository projections,
      DomainEventPublisher publisher,
      PaymentGateway gateway,
      Clock clock) {
    return new CancelBookingService(
        transactions, bookings, inventory, projections, publisher, gateway, clock);
  }

  @Bean
  ExpireBookingsUseCase expireBookingsUseCase(
      TransactionRunner transactions,
      BookingRepository bookings,
      InventoryRepository inventory,
      DomainEventPublisher publisher,
      Clock clock) {
    return new ExpireBookingsService(transactions, bookings, inventory, publisher, clock);
  }

  @Bean
  GetBookingUseCase getBookingUseCase(BookingRepository bookings) {
    return new GetBookingService(bookings);
  }

  @Bean
  ListMyBookingsUseCase listMyBookingsUseCase(BookingRepository bookings) {
    return new ListMyBookingsService(bookings);
  }

  @Bean
  GetAvailabilityUseCase getAvailabilityUseCase(
      EventProjectionRepository projections, InventoryRepository inventory, Clock clock) {
    return new GetAvailabilityService(projections, inventory, clock);
  }

  @Bean
  ReconcileRefundsUseCase reconcileRefundsUseCase(
      TransactionRunner transactions,
      BookingRepository bookings,
      ReconciliationRepository reconciliation,
      DomainEventPublisher publisher,
      PaymentGateway gateway,
      Clock clock,
      ReconcilerPolicy policy) {
    return new ReconcileRefundsService(
        transactions, bookings, reconciliation, publisher, gateway, clock, policy);
  }

  @Bean
  ApplyEventPublishedUseCase applyEventPublishedUseCase(
      TransactionRunner transactions,
      EventProjectionRepository projections,
      InventoryRepository inventory,
      ProcessedMessageRepository processed) {
    return new ApplyEventPublishedService(transactions, projections, inventory, processed);
  }

  @Bean
  ApplyEventRescheduledUseCase applyEventRescheduledUseCase(
      TransactionRunner transactions,
      EventProjectionRepository projections,
      ProcessedMessageRepository processed) {
    return new ApplyEventRescheduledService(transactions, projections, processed);
  }

  @Bean
  ApplyEventCancelledUseCase applyEventCancelledUseCase(
      TransactionRunner transactions,
      EventProjectionRepository projections,
      BookingRepository bookings,
      InventoryRepository inventory,
      ProcessedMessageRepository processed,
      DomainEventPublisher publisher,
      Clock clock,
      BookingProperties properties) {
    return new ApplyEventCancelledService(
        transactions,
        projections,
        bookings,
        inventory,
        processed,
        publisher,
        clock,
        properties.cascade().batchSize());
  }
}
