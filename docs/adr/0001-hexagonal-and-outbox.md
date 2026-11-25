# 0001. Arquitectura hexagonal y outbox transaccional

- Estado: aceptada
- Fecha: 2026-11-24
- Alcance: `catalog-service`; el patron se repite en `booking-service` y `notification-service`.

## Contexto y problema

`catalog-service` es dueno de los eventos y sus zonas, y avisa a otros servicios (reservas,
notificaciones) cuando un evento se publica, se cancela o se reprograma. Hay dos problemas:

1. El modelo de negocio (estados, invariantes, reglas de publicacion) debe poder probarse sin
   Spring ni base de datos, y no debe acoplarse a JPA, a HTTP ni al broker.
2. Guardar el cambio y avisar al broker son dos escrituras en sistemas distintos. Si el proceso
   cae entre ambas, o el broker no responde, un servicio se queda sin el aviso (o recibe uno de un
   cambio que no ocurrio).

## Alternativas consideradas

Estructura:
1. **Capas clasicas** (controller, service, repository) con las entidades JPA como modelo.
   Menos codigo, pero el modelo depende de JPA y las reglas se dispersan.
2. **Hexagonal**: dominio puro, casos de uso con puertos, adaptadores en `infrastructure`.

Publicacion de eventos:
1. Publicar al broker dentro del caso de uso, tras el commit. Se pierde el aviso si el proceso cae.
2. Transaccion distribuida (XA) entre base de datos y broker. Fragil y no soportada de forma
   uniforme (RabbitMQ y Azure Service Bus).
3. **Outbox transaccional** con un relay que lee la tabla y publica.
4. CDC (por ejemplo Debezium). Entrega fuerte, pero anade infraestructura que el MVP no necesita.

## Decision

**Hexagonal** con `domain` (solo JDK), `application` (casos de uso y puertos) e `infrastructure`
(adaptadores web, persistencia, mensajeria y configuracion). Las dependencias van hacia dentro y se
verifican en cada build con ArchUnit (`ArchitectureTest`). Las entidades JPA son distintas de los
aggregates y se mapean a mano. La API es contract-first: los controllers implementan interfaces
generadas desde `contracts/openapi/catalog-api.yaml`.

**Outbox transaccional**: el aggregate y su fila en `outbox_event` se guardan en la misma
transaccion. Un relay (`OutboxRelay`) las envia a RabbitMQ a traves de Spring Cloud Stream:

- Bloquea un lote con `FOR UPDATE SKIP LOCKED`, de modo que varias instancias no toman la misma fila.
- `messageId` es el id de la fila y no cambia entre reintentos.
- Marca `published_at` **solo tras la confirmacion del broker** (publisher confirm, envio
  sincronico con timeout). La entrega es **at-least-once**: si el proceso cae entre la confirmacion y
  el commit, la fila se reenvia con el mismo `messageId`.
- El orden es **por agregado**: una fila solo se envia si no queda otra anterior sin publicar del
  mismo agregado. Se procesa en `created_at, id` y el lote se corta en el primer fallo.
- Un fallo registra `attempts`, `last_error` y `next_attempt_at` (backoff exponencial). Al agotar los
  intentos la fila queda **aparcada**: no se reintenta y sigue bloqueando a su agregado.
- Metricas: `outbox.pending`, `outbox.parked`, `outbox.oldest.age`.

Los consumidores deben ser idempotentes (por `messageId`, y por el id de negocio cuando un mensaje
puede reemitirse; ver ADR 0003).

## Consecuencias

- Ningun aviso se pierde ni se inventa: o se guardan el cambio y su fila, o ninguno de los dos
  (probado con fallos reales de insercion y de commit, `OutboxAtomicityIT`).
- Pueden llegar duplicados; es el precio del at-least-once y por eso hay idempotencia en el consumidor.
- Hay latencia entre el cambio y el aviso (intervalo del poller, 1 s por defecto).
- Una fila aparcada detiene a su agregado hasta que una persona la resuelva. Es deliberado: enviar el
  `reschedule` de un evento cuyo `publish` se perdio dejaria a los consumidores con una historia
  imposible. El procedimiento esta en el README del servicio.
- Un confirm del broker significa que lo acepto y lo enruto, no que llegue a una cola: sin cola
  enlazada, RabbitMQ lo descarta. El binder no expone los mensajes devueltos, asi que no hay metrica de
  mensajes no enrutables. Ver ADR 0003 para cubrir el arranque de un consumidor nuevo.
- El relay envia como maximo un evento por agregado en cada pasada; el poller repite pasadas mientras
  haya progreso.

## Deuda tecnica

- **networknt 2.0.1 / Jackson 2, solo en test.** `openapi-request-validator` (conformidad de respuestas
  con el OpenAPI) arrastra `networknt:json-schema-validator` 2.0.1 y Jackson 2. La 3.x (Jackson 3) no
  puede convivir con ella (`NoSuchMethodError`, comprobado). En produccion todo JSON usa Jackson 3. Se
  revisa cuando el validador OpenAPI soporte Jackson 3; entonces los tests de esquemas pasan a la 3.x.
- **Modulo de test compartido de contratos (futuro).** Hoy `EventSchemas` (carga de
  `contracts/events` y validacion de payloads) vive en `catalog-service`. Cuando `booking-service` y
  `notification-service` necesiten el mismo test de contrato como productores y consumidores, se
  extrae a un modulo de test compartido (`contracts-test`) para no duplicarlo y para fijar en un unico
  sitio la version de la libreria de esquemas.
- Sin metrica de mensajes no enrutables (limite del binder).
- La reemision administrativa (ADR 0003) esta implementada; falta aun la idempotencia por `eventId` en los
  consumidores, que pertenece a `booking-service` y `notification-service`.
