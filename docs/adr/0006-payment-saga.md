# 0006. Mini-saga de pago y reembolso (y por que se cobra fuera de la transaccion)

- Estado: aceptada (se completa en el hito B8 con la evidencia de las carreras de B7)
- Fecha: 2026-11-25
- Alcance: `booking-service`

## Contexto y problema

Pagar una reserva mueve dinero en un sistema que no controlamos (la pasarela) y cambia estado en
nuestra base de datos. No hay transaccion que abarque las dos cosas, y la reserva puede dejar de ser
pagable mientras se cobra (expira, la cancela el usuario, se cancela el evento). Hay que garantizar,
en cualquier orden de los hechos y aunque el proceso caiga a mitad:

- nunca un cobro sin reserva confirmada **ni** reembolso en camino;
- nunca una reserva confirmada sin cobro;
- el inventario cuadra (`reserved` = entradas de las reservas `PENDING` y `CONFIRMED`).

La pasarela de este proyecto es siempre simulada (`FakePaymentGateway`); el diseno es el que se
usaria con una real.

## Alternativas consideradas

1. **Cobrar dentro de la transaccion de la reserva.** Simple, pero mantiene filas bloqueadas durante una
   llamada externa lenta o colgada, y si la transaccion falla despues de cobrar el dinero ya se movio:
   no hay rollback posible de un cobro.
2. **Confirmar primero y cobrar despues.** Deja reservas confirmadas sin cobro si el cobro falla.
3. **Mini-saga con intencion de cobro persistida** (elegida): una transaccion corta registra la
   intencion, el cobro ocurre fuera de toda transaccion, otra transaccion corta aplica el resultado, y
   un reconciliador repara lo que ninguna peticion termina.

## Decision

### `pay` en tres pasos (`PayBookingService`, no transaccional)

1. **Transaccion corta**: valida propiedad y estado (`PENDING`, `now < expiresAt`; si ya esta
   `CONFIRMED` devuelve la misma reserva sin cobrar) y guarda el `payment` en estado interno
   `INITIATED`. Un cobro jamas existe sin rastro en nuestra base de datos. Un indice unico parcial
   (`uq_payment_active_per_booking`) impide dos intentos vivos por reserva: el segundo `pay` concurrente
   responde 409 `payment-in-progress`.
2. **Cobro fuera de la transaccion**, con `paymentId` como clave de idempotencia. Un reintento tras un
   rechazo es un intento nuevo (otro `paymentId`); repetir el mismo intento no cobra dos veces. No se
   mantiene ningun lock durante la llamada.
3. **Transaccion corta que aplica el resultado**, recargando la reserva: si sigue `PENDING` y vigente,
   `CONFIRMED` + pago `CAPTURED` + `BookingConfirmed(paymentId)`; si ya no es pagable, el dinero se
   devuelve (pago `REFUND_PENDING`, reembolso, `REFUNDED`, `BookingPaymentRefunded`) y la respuesta es
   409 `payment-refunded`. Un conflicto de version se reintenta recargando: es lo que convierte una
   carrera perdida en el resultado correcto en lugar de un error.

El **motivo del reembolso** se deriva del estado final de la reserva: `EXPIRED` (o `PENDING` con la
retencion vencida) -> `BOOKING_EXPIRED_DURING_PAYMENT`; `CANCELLED` por el usuario ->
`BOOKING_CANCELLED`; `CANCELLED` por el evento -> `EVENT_CANCELLED`.

El estado resultante es siempre uno de dos: (`CONFIRMED`, `CAPTURED`, inventario retenido) o
(`EXPIRED`/`CANCELLED`, `REFUNDED`, inventario liberado). Mientras un reembolso esta `REFUND_PENDING`
el dinero esta en camino de vuelta, no perdido.

### Resultado desconocido del cobro

Una excepcion o un timeout de la pasarela **no es un rechazo**: no sabemos si el dinero se movio. El
pago queda `INITIATED`, la respuesta es 409 `payment-in-progress` y un **reconciliador** lo resuelve
despues: repite el `charge` (idempotente por `paymentId`, devuelve el mismo resultado) y aplica el
resultado exactamente como lo habria hecho la peticion original: confirmar si la reserva sigue
`PENDING` y vigente, reembolsar en cualquier otro caso. Cada fallo registra `attempts`,
`last_error` y `next_attempt_at` con backoff exponencial (umbrales en `@ConfigurationProperties`).

**Con una pasarela real**, el reconciliador no repetiria el cobro a ciegas: **consultaria por clave de
idempotencia** (`paymentId`) el estado del cobro y solo decidiria a partir de esa respuesta; repetir el
`charge` queda como ultimo recurso si el proveedor no ofrece consulta. La simulada es idempotente por
clave, asi que repetirlo equivale a consultar.

### Quien reembolsa y cuando

- **`pay`** (reserva ya no pagable) y **cancelacion del usuario** de una reserva `CONFIRMED`: el
  reembolso se intenta de inmediato, fuera de la transaccion. Si falla, el pago queda `REFUND_PENDING`.
- **Cascada de `EventCancelled`**: nunca llama a la pasarela. Cancela cada reserva en su propia
  transaccion, libera el inventario y deja el pago `REFUND_PENDING`; el reconciliador reembolsa. Asi un
  evento con miles de reservas no depende de que la pasarela responda para terminar.
- El **reconciliador** completa todo reembolso pendiente con backoff y publica `BookingPaymentRefunded`
  al conseguirlo. Metricas: `refund_pending` e `initiated_stale`.

### `TransactionRunner` como puerto (en lugar de `@Transactional`)

El caso de uso necesita **varias transacciones cortas con una llamada externa entre ellas**.
`@Transactional` sobre el servicio no sirve (una sola transaccion) y repartirlo en beans auxiliares
solo para esquivar la auto-invocacion oculta la forma del algoritmo. Se modela como puerto de salida
`TransactionRunner.execute(Supplier)`:

- la capa de aplicacion sigue sin anotaciones de Spring y el algoritmo se lee de arriba abajo;
- los fakes en memoria implementan **rollback real** (snapshot y restauracion), de modo que los tests
  unitarios prueban tambien que un fallo a medias no deja nada retenido ni publicado;
- el adaptador (`SpringTransactionRunner`, sobre `TransactionTemplate`) traduce los fallos que solo
  aparecen al **confirmar** (restriccion diferida, fallo de serializacion, deadlock) al mismo
  `ConcurrencyConflictException` que los repositorios lanzan dentro del bloque (hacen `flush` antes de
  volver), de modo que quien llama maneja un unico tipo de fallo.

Se aparta de `catalog-service`, que usa `@Transactional` en los servicios, porque catalog no tiene
llamadas externas dentro de un caso de uso. No se modifica catalog.

### Idempotency-Key concurrente

Dos `POST /bookings` simultaneos con la misma clave: el segundo viola `uq_booking_user_idempotency`, y en
PostgreSQL ese fallo **aborta la transaccion**; no se puede releer dentro de ella. `CreateBooking` inserta
la reserva **antes** de reservar las entradas (si pierde la carrera no retuvo nada), deja que la
transaccion termine en rollback y **relee en una transaccion nueva**, devolviendo la reserva ganadora
(200, `created = false`). Misma clave con otro cuerpo (huella SHA-256 distinta) -> 409
`idempotency-key-reuse`.

### El token de pago

El token opaco `tok_...` (nunca datos de tarjeta) se guarda solo mientras el pago esta `INITIATED`,
porque es lo unico que permite repetir un cobro desconocido. En cuanto el pago sale de ese estado
(`CAPTURED`, `DECLINED`, y por tanto tambien `REFUND_PENDING` y `REFUNDED`) el dominio lo **borra**, y una
restriccion de base de datos (`ck_payment_token_only_while_initiated`) impide que exista fuera de
`INITIATED`. `ChargeRequest.toString()` lo enmascara y no se escribe en ningun log.

### Pagar hasta `expiresAt`, aunque el evento ya haya empezado

La retencion decide si se puede pagar: `now < expiresAt`. El inicio del evento solo se valida al crear
la reserva (`EVENT_NOT_ON_SALE`) y al cancelar una `CONFIRMED` (`CANCEL_AFTER_START`). Una reserva creada
antes del inicio puede pagarse dentro de su retencion de 10 minutos aunque el evento empiece en ese
intervalo. Es una decision de producto, no un olvido: evita perder una venta ya retenida por unos
minutos y mantiene `pay` sin regla de negocio adicional, de modo que el contrato (`booking-api.yaml`,
que no declara 422 para `pay`) no cambia.

### El reembolso inmediato es de mejor esfuerzo

Tras el primer commit (la reserva `CANCELLED`, o el pago `CAPTURED` de una reserva que ya no era
pagable), el reembolso inmediato y su registro **no pueden convertir en error algo ya confirmado**.
`TransactionRunner` traduce todo fallo de base de datos que no es una carrera perdida a
`PersistenceFailureException` (un tipo de la aplicacion, sin Spring); `RefundSettler` captura **solo**
ese tipo y el error de la pasarela (nunca `catch (Exception)`). Si el reembolso no se completa, el pago
queda `REFUND_PENDING`, se registra un log y se incrementa `payments.refund.immediate.failed`, y el
reconciliador lo termina:

- `cancel` devuelve **200** con la reserva `CANCELLED` y el pago `REFUND_PENDING`.
- `pay` con la reserva ya no pagable responde **409 `payment-refunded`** si el pago quedo `REFUNDED` y
  **409 `payment-refund-pending`** si sigue pendiente. Ambos son 409 del contrato (que ya declara el
  409 de `pay`): solo cambia el `type`. El cliente nunca ve un 500 por un cobro que ya se decidio.

Un fallo **antes** del primer commit (guardar la intencion, o aplicar el resultado del cobro) si
termina la peticion (500): no hay nada confirmado que proteger, y el pago `INITIATED` lo resuelve el
reconciliador.

### Orden de los eventos de una reserva: `created_at = max(reserva) + 1 microsegundo`

El relay envia las filas de una reserva en orden `created_at, id`, y `id` es un UUID aleatorio: si dos
eventos de la misma reserva comparten `created_at`, el orden de envio es **arbitrario**, y un
`BookingConfirmed` podria llegar antes que su `BookingCreated`. Pasa con un reloj que no avanza entre
dos transacciones (tests), pero tambien con relojes distintos entre instancias del servicio: una
instancia adelantada escribe un evento y otra, atrasada, escribe el siguiente con un instante anterior.

Por eso `OutboxDomainEventPublisher` no se fia del reloj: cada fila recibe
`GREATEST(ahora, max(created_at de esa reserva) + 1 us)`, calculado en la misma sentencia `INSERT`. Los
escritores de una misma reserva estan serializados por el reclamo de version de la fila `booking`, de
modo que el maximo que lee es el del evento anterior. Un indice `(aggregate_id, created_at DESC)`
(migracion V2) sirve la consulta, porque el indice parcial de V1 solo cubre filas sin publicar.

Solo se escalona `created_at`. `next_attempt_at` es el reloj, de modo que una fila esta vencida de
inmediato aunque su instante de orden vaya unos microsegundos por delante. Un primer intento reutilizaba
`created_at` para ambos y las filas "futuras" no se enviaban: lo detecto un test con un reloj fijo.

Se aparta de `catalog-service`, que solo separa por un microsegundo los eventos de **una misma
llamada**. Catalog no se modifica; el mismo riesgo existe alli y se anota para una decision aparte.

## Consecuencias

- Tres transacciones cortas en lugar de una larga: mas piezas, pero ningun lock se mantiene durante una
  llamada externa y cada paso es reanudable por el reconciliador.
- Todo estado intermedio es observable (`INITIATED`, `REFUND_PENDING`) y tiene un reparador explicito.
- El reconciliador es parte del producto, no un parche: hay que operarlo (metricas, runbook en el
  README del servicio, hito B8).

## Evidencia (tests y mutaciones)

- `PayBookingServiceTest` (fase roja contra un esqueleto: 21 de 21 fallaban): los tres pasos, el
  rechazo, la repeticion, el resultado desconocido antes y despues de cobrar, el segundo `pay` concurrente
  (`payment-in-progress`) y el reembolso por expiracion, cancelacion del usuario y del evento durante el
  cobro. `InstrumentedGateway` comprueba en cada test que el gateway no se llama dentro de una
  transaccion y que el pago `INITIATED` ya estaba guardado.
- `TransactionBoundariesIT` (PostgreSQL real): `isActualTransactionActive() == false` durante `charge` y
  `refund`; fallo de commit traducido a `ConcurrencyConflictException`; 16 peticiones con la misma
  `Idempotency-Key` -> una reserva, entradas retenidas una vez.
- `ReconcileRefundsServiceTest`: reembolsos pendientes (incluidos los de la cascada), backoff
  exponencial con tope, huerfanos confirmados / reembolsados / rechazados / no resueltos.
- Reembolso de mejor esfuerzo: `OutboxAtomicityIT` cancela con el outbox fallando al registrar el
  reembolso (200, `REFUND_PENDING`, metrica +1, el reconciliador lo termina) y hace lo equivalente en
  `pay` (409 `payment-refund-pending`); `TransactionBoundariesIT` comprueba que un fallo de base de datos
  dentro del bloque y uno al confirmar llegan como `PersistenceFailureException` y una carrera perdida
  como `ConcurrencyConflictException`.
- Orden por reserva: `OutboxPublisherIT` escribe eventos de la misma reserva con el reloj quieto y con el
  reloj retrocediendo, y comprueba que `created_at` crece estrictamente.
- Mutaciones comprobadas: quitar el reembolso compensatorio de `pay` (fallan 4 tests), quitar el
  registro de backoff del reconciliador (fallan 3), romper la resolucion de huerfanos aprobados
  (fallan 6), liberar el inventario dos veces en la cascada (fallan 8).
