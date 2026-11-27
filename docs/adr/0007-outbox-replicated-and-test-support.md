# 0007. Outbox replicado frente a libreria compartida, libs/contract-testing y soporte de test duplicado

- Estado: aceptada
- Fecha: 2026-11-26
- Alcance: `catalog-service`, `booking-service` y el futuro `notification-service`

## Contexto y problema

`booking-service` necesita el mismo outbox transaccional que `catalog-service` (fila en la misma
transaccion, relay con `FOR UPDATE SKIP LOCKED`, confirm del broker, backoff, orden por agregado). La
tentacion es extraerlo a una libreria compartida. Hay que decidir si hacerlo ahora, y que hacer con lo que
ya se comparte y con lo que se duplica en los tests.

## Alternativas consideradas

1. **Libreria compartida del outbox ya** (modulo `libs/outbox`). Una sola implementacion que corregir.
   Pero con dos usuarios no sabemos aun que varia de verdad: el envelope y el mapeo de eventos son del
   servicio, y una abstraccion prematura se escribe a ciegas y luego estorba.
2. **Replicar y adaptar** (elegida), con una regla para saber cuando extraer.
3. Copiar sin disciplina: descartada; la replica debe poder compararse y la regla de extraccion, escribirse.

## Decision

**El outbox se replica en `booking-service` y se extrae al tercer usuario.** La replica es mecanica; medida
tras normalizar los nombres de servicio, la diferencia entre ambos servicios es:

| Clase | Lineas distintas | Es de dominio de cada servicio |
|---|---|---|
| `BrokerPublishException`, `OutboxBroker`, `OutboxMessage`, `OutboxMetrics`, `OutboxProperties` | 0 | no |
| `OutboxPoller`, `StreamBridgeOutboxBroker` | 2 | no |
| `OutboxRelay` | 10 | no |
| `RoutingKeys` | 7 | si (los eventos que emite cada servicio) |
| `OutboxDomainEventPublisher`, `OutboxEnvelopeFactory` | 63 y 124 | si (mapeo evento a envelope) |

Es decir, el relay, el poller, las propiedades y las metricas son **practicamente identicos** y seran la
libreria; el envelope y el publisher son especificos y se quedan en cada servicio detras de un puerto.
Criterio de extraccion: **al tercer usuario** (notification-service no emite eventos de dominio, asi que
aun no cuenta; el siguiente productor si). Hasta entonces, un cambio en el relay se aplica en **los dos**
servicios y el mismo tipo de test (`OutboxRelayIT`, `OutboxAtomicityIT`) lo ejerce en cada uno.

### `libs/contract-testing` (extraido ya)

Si se extrajo el modulo de **test** `libs/contract-testing`: `EventSchemas` (carga de los JSON Schema de
`contracts/events/` y validacion de payloads) y `OpenApiContracts` (conformidad de respuestas contra el
OpenAPI). Es seguro compartirlo ahora porque no tiene logica de servicio, solo lee `contracts/`, y evita
que cada servicio interprete los contratos a su manera. Lleva la excepcion de Jackson de CLAUDE.md
(networknt 2.x con Jackson 2 solo en el classpath de test).

### Soporte de test duplicado: a extraer con notification-service

Hoy hay **copias casi iguales** en `services/*/src/test/.../support`, todas por la misma razon (todavia no
hay un tercer usuario):

| Clase | Lineas distintas entre servicios | Nota |
|---|---|---|
| `TestOutboxSettings` | 0 | identica |
| `ControllableOutboxBroker` | 16 | interruptores para fallar o retener envios |
| `SharedPostgres`, `SharedRabbit` | 16 y 19 | contenedor por JVM; booking ajusta pool y `max_connections` |
| `TestTokens` | 37 | emision de JWT de prueba; booking anade usuarios |

Plan: cuando `notification-service` (WebFlux, tercer consumidor) necesite Postgres y Rabbit en tests, se
extrae un modulo de test `libs/test-support` con `SharedPostgres`, `SharedRabbit`, `ControllableOutboxBroker`,
`TestOutboxSettings` y `TestTokens`, parametrizados por nombre de base y ajustes. Hasta entonces la
duplicacion queda **registrada aqui** para que no se pierda, y se cambia en ambos servicios a la vez.

## Consecuencias

- Un bug del relay hay que corregirlo dos veces mientras haya dos copias; la tabla de arriba dice cuanto
  divergen, y un `diff` normalizado es la forma de comprobar que siguen alineadas.
- Los contratos tienen **un** lector (`libs/contract-testing`), no uno por servicio.
- Al extraer, el relay y las propiedades pasan a la libreria sin cambios de comportamiento, y los dos
  servicios conservan su envelope y su `RoutingKeys`.
