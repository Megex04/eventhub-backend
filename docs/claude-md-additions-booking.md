# Anexo para CLAUDE.md: reglas de `booking-service`

> Agrega este bloque al final de `CLAUDE.md` (o pidele a Claude Code que lo integre en las secciones
> correspondientes). No sustituye las reglas existentes: las **amplia** para `booking-service`.

## booking-service: reglas especificas

### Inventario y concurrencia
- El inventario vive en `booking-service` como **proyeccion local** (`event_projection`, `zone_inventory`) alimentada por eventos
  de catalog. Prohibido llamar de forma sincrona a `catalog-service` para reservar.
- **Reservar es una operacion atomica en SQL**, no leer-modificar-escribir:
  `UPDATE zone_inventory SET reserved = reserved + :qty, version = version + 1
   WHERE event_id = :e AND zone_id = :z AND reserved + :qty <= capacity` y comprobar filas afectadas (0 = sin inventario).
- Liberar: `reserved = reserved - :qty WHERE reserved - :qty >= 0`. Solo se libera en la **transicion** de estado que lo justifica
  (expirar, cancelar), dentro de la misma transaccion; nunca dos veces.
- Con varias zonas en una reserva, actualizar siempre en **orden por `zoneId`** (evita deadlocks).
- Invariante global (con test de estres): `reserved` de cada zona == suma de las cantidades de reservas `PENDING` y `CONFIRMED` de esa zona,
  y `0 <= reserved <= capacity`.

### Proyeccion alimentada por eventos
- Consumidores **idempotentes en dos niveles**: `processed_message(message_id)` y upserts por clave de negocio (`eventId`, `eventId+zoneId`).
- Una reemision (`/actuator/replay` de catalog) **no puede reiniciar `reserved`** ni reabrir un evento `CANCELLED`.
- Proyeccion monotona: se guarda `last_occurred_at` y se ignora todo mensaje con `occurredAt` anterior al ya aplicado; `CANCELLED` es terminal.
- `EventCancelled`: cancela las reservas `PENDING` y `CONFIRMED` del evento (motivo `EVENT_CANCELLED`), libera inventario y reembolsa las `CONFIRMED`.
- Cola y DLQ del consumidor se declaran **al arrancar**. Mensaje invalido o venenoso -> reintentos con backoff -> DLQ; nunca bloquear la cola.

### Reservas
- Estados: `PENDING -> CONFIRMED | EXPIRED | CANCELLED`; `CONFIRMED -> CANCELLED`. `EXPIRED` y `CANCELLED` son terminales.
- Retencion 10 min y maximo 6 entradas totales por reserva, **configurables** (`@ConfigurationProperties`), nunca constantes magicas en el dominio.
- `POST /bookings` exige `Idempotency-Key`; unicidad `(user_id, idempotency_key)`. Misma clave y mismo cuerpo -> misma reserva (200);
  misma clave y otro cuerpo -> 409 `idempotency-key-reuse`. Se compara por huella (hash) normalizada del cuerpo.
- Los precios salen de la proyeccion, nunca del request. `userId` sale del claim `sub`; una reserva ajena responde 404.
- Expiracion: job programado con `FOR UPDATE SKIP LOCKED` por lotes; seguro con varias instancias; usa `Clock` inyectado.

### Pagos (simulados) y reembolsos
- Puerto de salida `PaymentGateway` con `charge` y `refund`, **ambos idempotentes** (clave de idempotencia explicita = `paymentId`, el id del intento de cobro;
  `charge` y `refund` son operaciones distintas con su propio espacio de claves). El dominio y la aplicacion no conocen detalles de la pasarela.
- Adaptador falso solo en perfiles `local`/`dev`/`test`: aprueba salvo `tok_declined`. Un arranque con perfil de produccion sin pasarela real **debe fallar**.
- **Nunca** recibir, loguear ni guardar datos de tarjeta; solo tokens opacos `tok_...`.
- Flujo `pay` (mini-saga):
  1. Validar propiedad, estado `PENDING` y `now < expiresAt` (si ya esta `CONFIRMED`, devolver la misma reserva sin cobrar).
  2. Transaccion corta: registrar la **intencion de cobro** (`payment` en estado interno `INITIATED`; **una sola intencion activa por reserva**, con indice unico parcial sobre `INITIATED`/`CAPTURED`/`REFUND_PENDING`). Asi un cobro jamas queda sin rastro en nuestra BD.
  3. Cobrar **fuera** de la transaccion de base de datos (no mantener locks durante una llamada externa), con clave de idempotencia = `paymentId` (asi un reintento tras un rechazo es un intento nuevo, y repetir el mismo intento no cobra dos veces).
  4. Aprobado -> transaccion corta: recargar con version; si sigue `PENDING` y no expiro -> `CONFIRMED` + pago `CAPTURED` + outbox `BookingConfirmed`.
  5. Si la reserva ya no es pagable (expiro, se cancelo, conflicto de version) -> **reembolsar**, pago `REFUNDED`, outbox `BookingPaymentRefunded`, responder 409 `payment-refunded`.
  6. Si el reembolso falla -> pago `REFUND_PENDING` y un **reconciliador** programado lo reintenta con backoff hasta lograrlo; metrica y alerta de pendientes.
  7. Rechazado -> pago `DECLINED` (interno), 402 `payment-declined`; la reserva sigue `PENDING` y se puede reintentar (nueva intencion).
- **Pagos huerfanos:** el reconciliador tambien revisa pagos `INITIATED` con antiguedad mayor a un umbral (el proceso pudo caer tras cobrar). Repite `charge`
  (idempotente, devuelve el mismo resultado) y resuelve: si cobro aprobado y la reserva sigue `PENDING` y vigente -> confirmar; en cualquier otro caso -> reembolsar.
- Estados de `payment`: internos `INITIATED`, `DECLINED`; visibles en la API `CAPTURED`, `REFUND_PENDING`, `REFUNDED`.
- Garantia a probar con tests deterministicos (pasarela controlable con latches, sin `sleep`): en toda carrera pago vs expiracion el resultado es
  **exactamente uno** de: (`CONFIRMED`, pago `CAPTURED`, inventario retenido) o (`EXPIRED`/`CANCELLED`, pago `REFUNDED`, inventario liberado). Nunca cobro sin entrada ni entrada sin cobro.

### Contratos y mensajeria
- Contratos: `contracts/openapi/booking-api.yaml` y los esquemas `booking-*.v1` de `contracts/events/`. No modificarlos sin avisar y explicar el motivo.
- Outbox: replicar (adaptar) la implementacion de `catalog-service`; **no extraer una libreria compartida todavia** (se evalua al tercer usuario). Si, extraer el
  modulo de **test** `libs/contract-testing` (carga de esquemas desde `contracts/` y validacion de conformidad OpenAPI).
- `messageId` de cada evento emitido = id de la fila de outbox (estable entre reintentos).

### Seguridad
- Cualquier usuario autenticado puede reservar; no se requiere rol especial. Lectura de disponibilidad publica (`security: []` en el contrato).
- `management` de Actuator fuera del puerto publico en perfiles no locales.
