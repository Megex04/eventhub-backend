# Prompt 02: esqueleto de `booking-service`

> Prerrequisitos: `catalog-service` (M1-M8) mergeado en `main` y etiquetado.
> Uso: crea la rama `feat/booking-service-skeleton` desde `main`, ejecuta `claude`, activa el **modo plan** (Shift+Tab) y pega el bloque.
> Antes: copia al repo `contracts/openapi/booking-api.yaml`, los esquemas de `contracts/events/` y agrega a `CLAUDE.md` el contenido de
> `docs/claude-md-additions-booking.md`. Revisa el diff de `booking-confirmed.v1` (ahora incluye `paymentId`) y del `gen_events.py`.

---

```text
Lee primero CLAUDE.md completo (incluido el anexo de booking-service), contracts/openapi/booking-api.yaml, los esquemas
booking-*.v1 y event-*.v1 de contracts/events/, y los ADR 0001-0004. Son la fuente de verdad: si algo de lo que te pido los
contradice, avisame antes de implementar.

OBJETIVO
Crear booking-service (Spring MVC + JPA + PostgreSQL) con arquitectura hexagonal, contract-first, outbox transaccional,
consumo idempotente de eventos de catalog, inventario concurrente seguro, expiracion de reservas, y pago simulado con
reembolso (mini-saga "pago vs expiracion"), siguiendo el mismo nivel de rigor que catalog-service.

PRIMERO: entra en modo plan. Presenta un plan por hitos (B1..B8, ajustando lo que consideres necesario), las dependencias
nuevas con su justificacion y las preguntas abiertas. No escribas codigo hasta que yo apruebe.

HITOS (un commit Conventional por hito; ./mvnw verify en verde al terminar cada uno)

B1. Build y entorno
- Modulo services/booking-service (puerto 8082), BD `booking` en el docker-compose, y el realm de Keycloak ampliado con dos usuarios
  normales de prueba (customer1, customer2) sin rol especial. Credenciales solo de desarrollo.
- Extrae a libs/contract-testing (scope test) lo reutilizable de catalog: carga de esquemas JSON desde ../../contracts/events y
  conformidad contra OpenAPI. catalog-service debe seguir en verde usandolo. NO extraigas el outbox (se replica).
- Aplica las mismas decisiones de build, formato, versiones y perfiles de catalog.

B2. Dominio (TDD, sin Spring)
- Booking (aggregate): id, userId, eventId, status, items (zoneId, zoneName, quantity, unitPrice), total, createdAt, expiresAt,
  confirmedAt/expiredAt/cancelledAt, cancelReason, payment, version. Maquina de estados del anexo.
- ZoneInventory (capacity, reserved, reserve/release con invariantes), EventProjection (estado OPEN/CANCELLED, fechas, last_occurred_at),
  Payment (INITIATED, DECLINED internos; CAPTURED, REFUND_PENDING, REFUNDED visibles), Money, excepciones de dominio (409 / 422 / 400 como en catalog).
- Politica configurable por objeto de valor: retencion (10 min por defecto) y maximo de entradas (6) -> sin constantes magicas.
- Reglas: zonas sin repetir, quantity >= 1, total de entradas <= maximo, evento a la venta (OPEN y startsAt futuro con Clock inyectado).
- Domain events sellados que mapean a los esquemas booking-*.v1 (incluye BookingConfirmed con paymentId y BookingPaymentRefunded).
- Tests exhaustivos de la maquina de estados (toda transicion invalida) y de las invariantes del inventario.

B3. Aplicacion
- Puertos de entrada/salida segun CLAUDE.md. Puerto PaymentGateway (charge/refund idempotentes), Clock, CorrelationIdProvider,
  repositorios, OutboxPort.
- Casos de uso: CreateBooking (con idempotencia), PayBooking (mini-saga del anexo), CancelBooking, ExpireBookings (lote), GetBooking,
  ListMyBookings, GetAvailability, ReconcileRefunds (incluye pagos huerfanos INITIATED), y los de consumo: ApplyEventPublished, ApplyEventRescheduled, ApplyEventCancelled.
- Fakes en memoria, incluido un FakePaymentGateway CONTROLABLE desde los tests (latches para pausar charge/refund, aprobar, rechazar, fallar refund).
- Suite abstracta de contrato por puerto (repositorios) ejecutada contra el fake y contra JPA.

B4. Persistencia
- Flyway: booking, booking_item, payment, zone_inventory, event_projection, processed_message, outbox_event (con las columnas
  finales de catalog), unicidad (user_id, idempotency_key), indices justificados.
- Reserva e inventario con el UPDATE condicional atomico del anexo, orden por zoneId, y versionado optimista del Booking (@Version)
  con la misma disciplina que catalog (ADR 0004: la version sube exactamente 1 por guardado).
- Tests con Testcontainers: stress de concurrencia (p. ej. 200 hilos compitiendo por 20 entradas: nunca se supera capacity,
  gana exactamente 20) y el invariante global reserved == suma de reservas PENDING+CONFIRMED.

B5. Web y seguridad
- Genera las interfaces desde contracts/openapi/booking-api.yaml; los controllers solo traducen y delegan.
- ProblemDetail con type propio por regla (insufficient-inventory, payment-declined, payment-refunded, idempotency-key-reuse, ...).
- Resource Server JWT; userId desde `sub`; reserva ajena -> 404; disponibilidad publica.
- Idempotency-Key: misma clave+cuerpo -> 200 con la misma reserva; misma clave+otro cuerpo -> 409.
- Tests MockMvc de cada endpoint y error del contrato + conformidad contra el OpenAPI.

B6. Mensajeria
- Outbox replicado de catalog (relay ordenado, backoff, aparcado, metricas, publishPending()).
- Consumidores Spring Cloud Stream de los tres eventos de catalog, con cola y DLQ declaradas al arrancar, reintentos con backoff y DLQ para mensajes venenosos.
- Idempotencia en dos niveles (processed_message + upserts por eventId) y proyeccion monotona (last_occurred_at; CANCELLED terminal).
- Tests: contrato de payloads consumidos y producidos (contra los esquemas), reemision de catalog sin reiniciar `reserved`,
  consumidor tardio, mensaje duplicado, mensaje fuera de orden, EventCancelled en cascada (cancela, libera y reembolsa), DLQ.

B7. Expiracion y mini-saga pago vs expiracion
- Job de expiracion con FOR UPDATE SKIP LOCKED y ExpireBookings por lotes; reconciliador de reembolsos y de pagos huerfanos con backoff.
- Tests DETERMINISTAS (latches en el FakePaymentGateway, sin sleep) de las carreras: pago aprobado justo al expirar; cancelacion durante el cobro;
  pago rechazado y reintento; pay repetido (idempotente, un solo cobro); reembolso fallido -> REFUND_PENDING -> reconciliador lo completa;
  caida simulada entre charge aprobado y la confirmacion -> pago INITIATED huerfano -> el reconciliador lo resuelve (confirmar o reembolsar).
  En CADA carrera el resultado es exactamente uno de los dos estados finales consistentes del anexo y el inventario cuadra.
- Mutaciones que demuestren que los tests detectan: quitar el reembolso compensatorio, cobrar dentro de la transaccion, liberar inventario dos veces.

B8. Calidad y documentacion
- ArchUnit (mismas reglas que catalog) y JaCoCo (falla si el dominio < 90 %).
- README del servicio: levantar entorno, flujo curl completo (token Keycloak, reservar, pagar, cancelar, ver disponibilidad), runbook de filas de
  outbox aparcadas y de pagos REFUND_PENDING.
- ADR 0005 (inventario como proyeccion local con UPDATE condicional), ADR 0006 (mini-saga pago/reembolso y por que se cobra fuera de la
  transaccion), ADR 0007 (outbox replicado vs libreria compartida; libs/contract-testing).
- docs/audit-claude-md.md ampliado con las reglas del anexo y su evidencia o desviacion.
- Prueba desde un clon limpio (docker compose down -v) con el flujo curl.

RESTRICCIONES
- No implementes notification-service. No modifiques catalog-service salvo adaptarlo a libs/contract-testing; si descubres una incoherencia en sus
  contratos o comportamiento, detente y preguntame.
- No modifiques contracts/ sin avisarme y explicar el motivo (muestrame el diff antes).
- Nunca datos de tarjeta; solo tokens `tok_...`. La pasarela falsa nunca debe poder activarse en un perfil de produccion.

CRITERIOS DE ACEPTACION
- docker compose up -d && ./mvnw verify en verde desde cero (incluye catalog).
- Todos los endpoints de booking-api.yaml implementados y cubiertos; respuestas conformes al OpenAPI.
- Payloads producidos y consumidos validan contra los JSON Schemas.
- Stress de inventario sin sobreventa y carreras pago/expiracion con resultados consistentes en cada ejecucion (repetir 10 veces sin fallos).
- ArchUnit en verde; dominio >= 90 %.
- Flujo de punta a punta: publicar un evento en catalog -> aparece disponibilidad en booking -> reservar -> pagar -> BookingConfirmed en RabbitMQ.

Al finalizar cada hito dame un resumen de 3-5 lineas: que hiciste, decisiones, y que sigue. Empieza con el plan.
```

---

## Seguimientos utiles

```text
Haz un code review estricto de la mini-saga de PayBooking: cada punto donde un fallo (excepcion, caida del proceso, timeout de la
pasarela) pueda dejar un cobro sin reserva confirmada ni reembolso. Para cada punto, indica el estado resultante y quien lo repara.
No cambies codigo todavia.
```

```text
Escribe un test de caos: matar el proceso (simulado) entre charge aprobado y la transaccion de confirmacion. Describe que reparador
detecta el pago huerfano (payment CAPTURED sobre reserva no confirmada) y haz que el reconciliador lo reembolse.
```
