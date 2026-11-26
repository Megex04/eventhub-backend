# 0008 — Consumo de eventos de catalog en booking-service

Estado: aceptada

## Contexto

booking-service mantiene una proyección local (`event_projection`, `zone_inventory`) alimentada por
`EventPublished`, `EventRescheduled` y `EventCancelled`. RabbitMQ entrega at-least-once y no garantiza
orden entre routing keys; catalog puede reemitir el estado actual (`/actuator/replay`).

## Decisión

- **Una cola, tres routing keys, un consumidor con `concurrency 1`.** Cola `booking-catalog-events`
  (`queue-name-group-only`) enlazada a `catalog.event.{published,rescheduled,cancelled}.v1`, `prefetch 1`.
  Un solo hilo da el orden de llegada del broker; la proyección se protege además con la regla monótona,
  no solo con el orden.
- **Reintentos en proceso con backoff y luego DLQ.** `max-attempts 5`, backoff 1 s x2 hasta 30 s;
  `auto-bind-dlq`, `republish-to-dlq`, `requeue-rejected=false`, DLQ `booking-catalog-events.dlq`.
  Cola y DLQ se declaran al arrancar el binding. El mensaje republicado conserva el cuerpo y añade
  las cabeceras `x-exception-*`.
- **Mensaje venenoso** (JSON roto, campo obligatorio ausente, tipo o versión desconocidos) lanza
  `PoisonMessageException`, no reintentable: va a la DLQ en el primer intento y no bloquea los siguientes.
- **Lector tolerante:** se ignoran los campos desconocidos y los que no se usan; falla si falta uno
  obligatorio. Versión distinta de 1 -> DLQ (un cambio incompatible llega como `v2` con otra routing key).
- **Idempotencia en dos niveles:** `processed_message` (se marca dentro de la transacción de aplicar, así
  un fallo no lo marca) y upserts por `eventId` y `eventId+zoneId` que **nunca tocan `reserved`**.
- **Proyección monótona:** se ignora todo mensaje con `occurredAt` anterior a `last_occurred_at`;
  `CANCELLED` es terminal. Una cancelación sin publicación previa crea un *tombstone*.
  Un conflicto de versión al guardar se reintenta recargando (3 veces) antes de fallar el mensaje.
- **Cascada de `EventCancelled`:** lotes de 100 reservas, una transacción por reserva, reanudable (lo ya
  cancelado no se repite). Los reembolsos quedan `REFUND_PENDING` y los termina el reconciliador: el
  consumidor nunca llama a la pasarela.

## Reschedule huérfano

Un `EventRescheduled` sin publicación previa no se puede aplicar. Con reintentos en proceso y un solo
consumidor, la publicación que le siga espera mientras el reschedule agota sus intentos, así que el
reschedule **termina en la DLQ** (es lo esperado, no un fallo). Se repara con la reemisión de catalog, que
lleva el estado actual (fechas nuevas incluidas); la DLQ queda como registro para el operador.
No se descarta en silencio: perder un cambio de fecha sería invisible.

## Métricas

`catalog.events.consumed{type}`, `catalog.events.failed{type}` (`unreadable` si no se pudo leer),
`catalog.events.dead.lettered`. El conteo de DLQ se hace en el consumidor (cuando el fallo es venenoso o
es el último intento, según la cabecera `deliveryAttempt`): un manejador en el canal de errores del binder
no se invocaba y, de rechazar el mensaje, lo sacaría del camino de DLQ del binder.

## Consecuencias

- La DLQ requiere operación humana (inspeccionar, reemitir desde catalog); no hay reproceso automático.
- Un mensaje que falla de forma transitoria retiene la cola hasta 1+2+4+8 s (15 s): aceptable con un solo
  consumidor y volumen bajo.
- Los tests desactivan el binding al arrancar (`TestConsumerSettings`) y solo `CatalogEventsConsumerIT`
  lo arranca, para que un contexto en caché no compita por la cola.
