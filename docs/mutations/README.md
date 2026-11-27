# Mutaciones de la saga de pago (booking-service)

Cada `.patch` introduce un bug a proposito. `run.sh` aplica uno a uno, ejecuta toda la suite contra
PostgreSQL y RabbitMQ reales (Testcontainers, hace falta Docker), cuenta los tests que fallan y
revierte el parche. Sale con codigo 1 si alguna mutacion no rompe nada. Exige arbol limpio.

```bash
docs/mutations/run.sh        # todas
docs/mutations/run.sh 02     # una
```

| Mutacion | Que rompe | Tests que fallan | Clases que la detectan |
|---|---|---|---|
| 01-no-compensating-refund | `pay` no reembolsa cuando la reserva ya no es pagable | 49 | BookingApiIT, OutboxAtomicityIT, PayBookingServiceTest, SagaRacesIT |
| 02-charge-inside-the-transaction | el cobro se hace dentro de la transaccion que registra la intencion (locks durante la llamada externa) | 71 | BookingApiIT, PayBookingServiceTest, ReconcileRefundsServiceTest, ReconcilerJobIT, SagaBacklogMetricsIT, SagaRacesIT, TransactionBoundariesIT |
| 03-release-inventory-twice | la expiracion libera el inventario dos veces | 57 | BookingApiIT, BookingEventsEndToEndIT, CancelBookingServiceTest, ExpirationJobIT, ExpireBookingsServiceTest, InventoryConcurrencyIT, OutboxAtomicityIT, OutboxRelayIT, ReconcileRefundsServiceTest, SagaBacklogMetricsIT, SagaRacesIT |

Resultado medido el 2026-11-26 sobre el commit `760f2a6`. Los conteos varian si cambia la suite; lo que
importa es que ninguno sea 0.
