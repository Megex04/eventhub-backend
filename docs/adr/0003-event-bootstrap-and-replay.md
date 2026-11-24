# 0003. Bootstrap y reemision de `EventPublished`

- Estado: aceptada (decision); **implementacion pendiente** en un hito posterior
- Fecha: 2026-11-24

## Contexto y problema

El exchange `eventhub.events` es de tipo `topic`. RabbitMQ solo entrega un mensaje a las colas que
**ya estan enlazadas** cuando llega; si no hay ninguna, lo confirma y lo descarta (ver ADR 0001). El
catalogo no declara colas de consumidores: cada consumidor declara la suya.

Consecuencia: cuando `booking-service` se despliega por primera vez (o se recrea su cola, o estuvo
caido con la cola borrada), los `EventPublished` anteriores no existen para el. No tendria inventario
de eventos ya publicados.

## Alternativas consideradas

1. **El catalogo declara las colas de los consumidores.** Se evitaria el hueco, pero acopla al
   catalogo con cada consumidor (nombres, DLQ, politicas) y rompe "una cola por consumidor+tema" a
   cargo de quien consume.
2. **Un consumidor nuevo pide un snapshot por REST al catalogo.** Funciona, pero introduce una
   dependencia sincrona entre servicios en el arranque, justo lo que la mensajeria evita.
3. **Reemision administrativa de `EventPublished`** desde el catalogo, con consumidores idempotentes
   por el id de negocio del evento.
4. Colas de tipo stream con relectura. Resuelve el replay, pero cambia el modelo de mensajeria de todos
   los servicios; fuera del alcance del MVP.

## Decision

Opcion 3, en tres reglas:

1. **El consumidor declara su cola al arrancar** (cola durable, enlace a `catalog.event.published.v1`,
   DLQ y reintentos con backoff) **antes** de pedir una reemision. Es parte de su arranque.
2. **El catalogo ofrece una reemision administrativa** (operacion restringida a un rol de operacion,
   no a `ORGANIZER`) que, para cada evento en estado `PUBLISHED`, escribe una **nueva** fila de outbox
   con un `EventPublished` que refleja el estado **actual** del evento (fechas, venue y zonas vigentes).
   Pasa por el mismo relay: mismo orden por agregado, mismas confirmaciones. Los eventos `CANCELLED` o
   `DRAFT` no se reemiten: no hay inventario que crear. La operacion admite filtrar por evento o por
   rango de fechas para no reenviar todo el catalogo.
3. **Los consumidores son idempotentes por el `eventId` de negocio**, no solo por `messageId`. Una
   reemision tiene un `messageId` nuevo (es otra fila de outbox) pero el mismo `eventId`; el consumidor
   crea el inventario con "insertar si no existe" sobre `eventId`, y trata un `EventPublished` repetido
   como una actualizacion o un no-op, nunca como un duplicado.

## Consecuencias

- Un consumidor nuevo se pone al dia sin acoplar el catalogo a sus colas ni abrir una llamada
  sincrona.
- Todos los consumidores de `EventPublished` deben implementar la idempotencia por `eventId`, ademas de
  `processed_message(message_id)`. Debe constar en los contratos de cada consumidor y probarse con un
  test de duplicado con distinto `messageId` y mismo `eventId`.
- La reemision tambien llega a los consumidores ya al dia; por eso la idempotencia no es opcional.
- Mientras no este implementada, la unica forma de poner al dia un consumidor nuevo es insertar filas
  de outbox a mano, lo que no se recomienda. Hasta entonces, el consumidor debe arrancar **antes** de que
  se publiquen eventos, o se acepta el hueco.
- Hace falta definir el rol de operacion y proteger la operacion (sin token, 401; sin rol, 403).
