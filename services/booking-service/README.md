# booking-service

Reservas de eventos de EventHub: Spring MVC + JPA + PostgreSQL, arquitectura hexagonal, API
contract-first (`contracts/openapi/booking-api.yaml`). El inventario es una **proyeccion local**
alimentada por los eventos de catalog; el pago es una **mini-saga** contra una pasarela simulada; los
eventos `BookingCreated`, `BookingConfirmed`, `BookingExpired`, `BookingCancelled` y
`BookingPaymentRefunded` salen por un **outbox transaccional**.

Decisiones: [`docs/adr`](../../docs/adr) (0005 inventario, 0006 saga de pago, 0007 outbox y libreria de
test, 0008 consumo de eventos). Auditoria contra CLAUDE.md:
[`docs/audit-claude-md.md`](../../docs/audit-claude-md.md). Mutaciones de la saga:
[`docs/mutations`](../../docs/mutations/README.md).

## Requisitos y entorno

Java 21 y Docker (Compose v2). Los comandos son de bash (Linux, macOS, WSL o Git Bash) y se lanzan
**desde la raiz del repositorio**. No hace falta `jq`: los ejemplos extraen campos con `sed`.

```bash
cp .env.example .env          # opcional: compose usa estos mismos valores por defecto
docker compose up -d          # postgres (bases catalog y booking), rabbitmq y keycloak
docker compose ps             # espera a que los tres esten "healthy" (keycloak tarda ~40 s)
```

La base `booking` la crea `docker/postgres/init-booking.sql` **solo cuando el volumen se crea por primera
vez**. En un volumen ya existente:
`docker compose exec postgres psql -U eventhub -d catalog -c "CREATE DATABASE booking;"`.

```bash
export POSTGRES_PASSWORD=eventhub_dev RABBITMQ_DEFAULT_PASS=eventhub_dev   # valores de .env.example
./mvnw -pl services/booking-service spring-boot:run -Dspring-boot.run.profiles=local
```

Escucha en `http://localhost:8082` (el perfil `local` sirve el actuator en ese mismo puerto; en los demas
perfiles el actuator va aparte, en el 8083). Arranca **despues** de catalog (8081) si quieres ver
disponibilidad: la proyeccion se llena con los eventos que catalog publica.

| Variable | Para que | Por defecto |
|---|---|---|
| `POSTGRES_PASSWORD` | contrasena de PostgreSQL (perfil `local`) | **ninguno** |
| `RABBITMQ_DEFAULT_PASS` | contrasena de RabbitMQ (perfil `local`) | **ninguno** |
| `OIDC_ISSUER_URI` | emisor de los JWT | `http://localhost:8080/realms/eventhub` en `local`; ninguno fuera |
| `POSTGRES_USER`, `POSTGRES_PORT`, `BOOKING_DB` | conexion | `eventhub`, `5432`, `booking` |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_HOST`, `RABBITMQ_PORT` | conexion | `eventhub`, `localhost`, `5672` |
| `KEYCLOAK_PORT` | puerto de Keycloak | `8080` |

Fuera del perfil `local` hay que dar `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_USER`,
`RABBITMQ_PASSWORD` y `OIDC_ISSUER_URI`, y **el arranque falla si no hay una pasarela de pago real**
(la simulada solo existe en `local`, `dev` y `test`). Nada de esto se versiona.

### Ajustes (`eventhub.*`)

| Propiedad | Valor | Que controla |
|---|---|---|
| `eventhub.booking.hold-duration` / `max-tickets` | `10m` / `6` | retencion de una reserva `PENDING` y entradas maximas por reserva |
| `eventhub.booking.expiration.enabled`, `poll-interval`, `batch-size` | `true`, `30s`, `50` | job de expiracion |
| `eventhub.booking.reconciler.enabled`, `poll-interval`, `batch-size` | `true`, `1m`, `20` | job que termina reembolsos y cobros huerfanos |
| `eventhub.booking.reconciler.initiated-stale-after` | `2m` | edad a partir de la cual un `INITIATED` se considera huerfano |
| `eventhub.booking.reconciler.initial-backoff` / `max-backoff` | `30s` / `10m` | espera tras un fallo, se duplica hasta el tope |
| `eventhub.booking.cascade.batch-size` | `100` | reservas canceladas por lote al cancelarse un evento |
| `eventhub.outbox.*` | ver `OutboxProperties` | `batch-size` 50, `poll-interval` 1s, `max-attempts` 10, `initial-backoff` 2s, `max-backoff` 5m, `confirm-timeout` 5s |
| `spring.cloud.stream.bindings.catalogEvents-in-0.consumer.*` | 5 intentos, 1s x2 hasta 30s | reintentos en proceso antes de la DLQ |

## Tests

```bash
./mvnw verify                                   # todo: unitarios, integracion (Testcontainers), ArchUnit, cobertura
./mvnw -pl services/booking-service -am verify  # solo booking (y lo que necesita)
docs/mutations/run.sh                           # mutaciones de la saga (necesita Docker y arbol limpio)
```

Los `*IT` usan PostgreSQL y RabbitMQ reales con Testcontainers. Las carreras de la saga
(`SagaRacesIT`) fuerzan cada intercalado con latches y se repiten 10 veces.

## Probar la API con curl

Usuarios del realm (solo desarrollo): `customer1` / `customer1_dev` y `customer2` / `customer2_dev`
(clientes: cualquier usuario autenticado puede reservar), `organizer` / `organizer_dev` (rol
`ORGANIZER`, para crear eventos en catalog) y `admin` / `admin_dev` (rol `ADMIN`). Client publico:
`eventhub-web`. El mismo recorrido, automatizado, esta en [`scripts/e2e-smoke.sh`](../../scripts/e2e-smoke.sh).

### 1. Tokens y cola de observacion

```bash
token() {  # token <usuario> <contrasena>
  curl -s -X POST http://localhost:8080/realms/eventhub/protocol/openid-connect/token \
    -d grant_type=password -d client_id=eventhub-web -d username="$1" -d password="$2" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
ORG=$(token organizer organizer_dev); CUS=$(token customer1 customer1_dev)

# Cola de prueba con los eventos de booking. Hazlo ANTES de reservar: sin cola enlazada RabbitMQ
# confirma y descarta (ver "Eventos no enrutables").
RMQ="-s -u eventhub:eventhub_dev -H content-type:application/json http://localhost:15672/api"
curl $RMQ/exchanges/%2F/eventhub.events -X PUT -d '{"type":"topic","durable":true}'
curl $RMQ/queues/%2F/demo.booking -X PUT -d '{"durable":true}'
curl $RMQ/bindings/%2F/e/eventhub.events/q/demo.booking -X POST -d '{"routing_key":"booking.#"}'
```

### 2. Crear y publicar un evento en catalog

```bash
CAT=http://localhost:8081/api/v1
STARTS=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ); ENDS=$(date -u -d '+30 days 4 hours' +%Y-%m-%dT%H:%M:%SZ)
EVENT_ID=$(curl -s -X POST $CAT/events -H "Authorization: Bearer $ORG" -H "Content-Type: application/json" -d '{
  "title":"Rock Fest","venue":"Estadio Nacional","startsAt":"'$STARTS'","endsAt":"'$ENDS'"}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
ZONE_ID=$(curl -s -X POST $CAT/events/$EVENT_ID/zones -H "Authorization: Bearer $ORG" -H "Content-Type: application/json" \
  -d '{"name":"General","price":{"amount":"150.00","currency":"PEN"},"capacity":50}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -X POST $CAT/events/$EVENT_ID/publish -H "Authorization: Bearer $ORG"
```

### 3. Disponibilidad (publica) y reserva

El relay de catalog corre cada segundo y booking consume en cuanto llega: espera un par de segundos.

```bash
BK=http://localhost:8082/api/v1
sleep 3; curl -s $BK/availability/$EVENT_ID                       # zonas con `available`

# reservar: Idempotency-Key obligatorio; los precios salen de la proyeccion, el usuario del token
BOOKING_ID=$(curl -s -X POST $BK/bookings -H "Authorization: Bearer $CUS" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)" \
  -d '{"eventId":"'$EVENT_ID'","items":[{"zoneId":"'$ZONE_ID'","quantity":2}]}' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s $BK/bookings/$BOOKING_ID -H "Authorization: Bearer $CUS"  # PENDING, expiresAt a 10 min
```

(Sin `/proc` (macOS, Git Bash) cae a `uuidgen`; `scripts/e2e-smoke.sh` genera el UUID con `od` y funciona en cualquiera.) Repetir la misma peticion con la
misma clave devuelve la misma reserva (200); la misma clave con otro cuerpo, 409 `idempotency-key-reuse`.

### 4. Pagar, ver el evento y cancelar

```bash
curl -s -X POST $BK/bookings/$BOOKING_ID/pay -H "Authorization: Bearer $CUS" \
  -H "Content-Type: application/json" -d '{"paymentToken":"tok_approved"}'   # CONFIRMED
curl $RMQ/queues/%2F/demo.booking/get -X POST \
  -d '{"count":10,"ackmode":"ack_requeue_false","encoding":"auto"}'          # BookingCreated, BookingConfirmed

curl -s -X POST $BK/bookings/$BOOKING_ID/cancel -H "Authorization: Bearer $CUS"   # CANCELLED + reembolso
sleep 3; curl $RMQ/queues/%2F/demo.booking/get -X POST \
  -d '{"count":10,"ackmode":"ack_requeue_false","encoding":"auto"}'          # BookingCancelled, BookingPaymentRefunded
```

`tok_declined` hace que la pasarela rechace (402 `payment-declined`; la reserva sigue `PENDING` y se puede
reintentar). Nunca se envian ni se guardan datos de tarjeta, solo tokens opacos `tok_...`.

### Errores

`application/problem+json` (RFC 9457), nunca con trazas; incluyen `traceId`. `type` es
`https://eventhub.dev/problems/<slug>`.

| Codigo | Cuando | slug |
|---|---|---|
| 400 | campo invalido, `Idempotency-Key` ausente o mal formada | `validation-error` |
| 401 | falta el token o no es valido | `unauthorized` |
| 402 | la pasarela rechazo el pago | `payment-declined` |
| 404 | reserva ajena o inexistente; evento desconocido o cancelado | `not-found` |
| 409 | transicion invalida, reserva expirada al pagar, pago en curso, reembolso, clave reutilizada, version obsoleta | `invalid-state`, `payment-in-progress`, `payment-refunded`, `payment-refund-pending`, `idempotency-key-reuse`, `concurrency-conflict` |
| 422 | sin inventario o regla de negocio | `insufficient-inventory` y otros |
| 500 | error inesperado (sin detalles internos) | `internal-error` |

## Observabilidad

Metricas (Prometheus, exige token): `outbox.pending`, `outbox.parked`, `outbox.oldest.age`,
`refund_pending`, `initiated_stale`, `bookings.pending.overdue`, `catalog.events.consumed{type}`,
`catalog.events.failed{type}`, `catalog.events.dead.lettered`. En condiciones sanas, **las tres de
alerta (`outbox.parked`, `refund_pending`, `initiated_stale`) y `bookings.pending.overdue` valen 0**;
una que se mantiene por encima de 0 es una alerta. Probes: `/actuator/health/{liveness,readiness}`.

## Runbooks

Las consultas SQL se lanzan con
`docker compose exec postgres psql -U eventhub -d booking` (en produccion, con la herramienta del
operador). Antes de tocar nada, mira el `last_error`.

### Outbox aparcado (`outbox.parked` > 0)

Una fila agoto `max-attempts` (por defecto 10) y **sigue bloqueando a los eventos posteriores de su
reserva** hasta que una persona la resuelva (el orden por agregado se respeta a proposito).

```sql
SELECT id, aggregate_id, event_type, attempts, last_error, next_attempt_at
  FROM outbox_event WHERE published_at IS NULL AND attempts >= 10 ORDER BY created_at;
```

1. Lee `last_error`: broker caido, confirm vencido, ruta inexistente.
2. Arregla la causa (broker, credenciales, exchange `eventhub.events`).
3. Reintenta: `UPDATE outbox_event SET attempts = 0, next_attempt_at = now(), last_error = NULL WHERE id = '<id>';`
   El relay la recoge en el siguiente `poll-interval`. El `messageId` no cambia, asi que los consumidores
   la tratan como duplicado si ya se entrego.
4. Comprueba que `outbox.parked` y `outbox.oldest.age` vuelven a 0.

### `REFUND_PENDING` atascado (`refund_pending` > 0 sostenido)

El dinero esta cobrado y la reserva ya no es pagable; el reconciliador reembolsa con backoff (30 s,
duplicando hasta 10 min). Si no avanza:

```sql
SELECT p.id, p.booking_id, p.refund_reason, p.attempts, p.last_error, p.next_attempt_at, b.status
  FROM payment p JOIN booking b ON b.id = p.booking_id WHERE p.status = 'REFUND_PENDING';
```

1. `last_error` dice que la pasarela no acepta el reembolso: arregla la pasarela (o el token/cuenta).
2. Para no esperar el backoff: `UPDATE payment SET next_attempt_at = now() WHERE id = '<paymentId>';`
3. El reembolso es **idempotente por `paymentId`**: reintentarlo es seguro. Si la pasarela confirma que ya
   devolvio el dinero, el siguiente intento lo registra (`REFUNDED` + `BookingPaymentRefunded`).
4. Nunca cambies el estado a mano: el evento `BookingPaymentRefunded` solo lo emite la transicion.

### `INITIATED` huerfano (`initiated_stale` > 0)

El proceso cayo o el gateway no contesto despues de cobrar. Mientras siga `INITIATED`, **`pay` de esa
reserva responde 409 `payment-in-progress`** (ver "Limitaciones"). El reconciliador lo resuelve solo,
repitiendo el `charge` (idempotente): confirma si la reserva sigue `PENDING` y vigente; en cualquier otro
caso reembolsa.

```sql
SELECT p.id, p.booking_id, p.created_at, p.attempts, p.last_error, p.next_attempt_at, b.status, b.expires_at
  FROM payment p JOIN booking b ON b.id = p.booking_id WHERE p.status = 'INITIATED' ORDER BY p.created_at;
```

1. Si es mas reciente que `initiated-stale-after` (2 min), **espera**: puede que el cobro siga en vuelo.
2. Si es mas viejo y no avanza, mira `last_error` (la pasarela sigue sin contestar) y `next_attempt_at`
   (backoff). Verifica que `eventhub.booking.reconciler.enabled=true` en esa instancia.
3. Para adelantar un reintento: `UPDATE payment SET next_attempt_at = now() WHERE id = '<paymentId>';`
4. No borres ni edites el pago: podria haber dinero cobrado sin rastro.

### Inspeccion y reenvio de la DLQ (`booking-catalog-events.dlq`)

Un mensaje de catalog llega a la DLQ si es venenoso (JSON roto, campo obligatorio ausente, tipo o version
desconocidos) o si agota los 5 intentos (por ejemplo, un `EventRescheduled` de un evento nunca publicado
aqui). La DLQ no se reprocesa sola; `catalog.events.dead.lettered` cuenta lo que entra.

```bash
# inspeccionar SIN sacar: se ve el cuerpo, la routing key original y x-exception-message
curl $RMQ/queues/%2F/booking-catalog-events.dlq/get -X POST \
  -d '{"count":10,"ackmode":"ack_requeue_true","encoding":"auto"}'
```

- **Estado desactualizado** (el caso habitual: un reschedule huerfano): no reenvies el mensaje, **reemite
  desde catalog** el estado actual, que ya trae las fechas nuevas: `POST /actuator/replay` con token
  `ADMIN` (ver el README de catalog). Despues vacia la DLQ.
- **Reenviar tal cual** (el consumidor estaba mal y ya esta arreglado): publica el cuerpo en la cola de
  origen por el exchange por defecto, que no toca a otros consumidores, y retira el mensaje de la DLQ:

```bash
curl $RMQ/exchanges/%2F/amq.default/publish -X POST \
  -d '{"properties":{"content_type":"application/json"},"routing_key":"booking-catalog-events","payload":"<cuerpo copiado>","payload_encoding":"string"}'
curl $RMQ/queues/%2F/booking-catalog-events.dlq/get -X POST -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}'
```

  Si es un mensaje venenoso de verdad, reenviarlo lo devuelve a la DLQ tras el primer intento: corrigelo en
  el productor, no aqui. La idempotencia (`processed_message`) hace seguro reenviar un mensaje que ya se
  aplico.

## Limitaciones conocidas

### Un pago en curso sin resolver bloquea `pay` de esa reserva

Solo puede haber **una intencion de cobro activa por reserva** (indice unico parcial sobre
`INITIATED`/`CAPTURED`/`REFUND_PENDING`). Si el proceso cae, o el gateway no responde, despues de
cobrar, el pago queda `INITIATED` y mientras siga asi un nuevo `POST /bookings/{id}/pay` responde
**409 `payment-in-progress`**: reintentar el cobro podria cobrar dos veces.

Lo resuelve el reconciliador, no el usuario: un `INITIATED` se considera huerfano cuando supera
`eventhub.booking.reconciler.initiated-stale-after` (por defecto 2 min) y el job lo recoge en su
siguiente pasada (`poll-interval`, por defecto 1 min). Es decir, **hasta ~`initiated-stale-after` +
`poll-interval` (unos 3 min con los valores por defecto)**, mas el backoff si el gateway sigue sin
contestar. Al resolverse, la reserva queda `CONFIRMED` (cobro aprobado y reserva vigente) o el cobro se
reembolsa; si la reserva expira antes (hold de 10 min), expira igual y el cobro se reembolsa.
Vigilar con el gauge `initiated_stale` (>0 de forma sostenida = el reconciliador no avanza).

### Eventos no enrutables

Un confirm del broker **no** implica que haya una cola destino: sin cola enlazada, RabbitMQ confirma y
descarta. Cada consumidor declara su cola al arrancar y **los eventos anteriores no se reenvian**:
- booking declara `booking-catalog-events` al arrancar; los eventos de catalog publicados con booking
  apagado **y nunca antes arrancado** se pierden para booking. Se recuperan con `POST /actuator/replay` de
  catalog (idempotente por clave de negocio, no reinicia `reserved` ni reabre un evento cancelado).
- los eventos de booking no tienen consumidor todavia (notification-service es posterior): hasta que
  exista su cola, se descartan en el broker aunque el outbox los marque publicados.

### El reintento en proceso retiene la cola

El consumidor de catalog es de **un solo hilo** con reintento en proceso (5 intentos, 1+2+4+8 = 15 s de
espera). Un mensaje que falla de forma transitoria **retiene la cola hasta 15 s**; con volumen bajo es
aceptable y mantiene el orden, pero un productor que emita muchos mensajes malos retrasaria los demas.
Los venenosos no se reintentan (a la DLQ en el primer intento). Un `EventRescheduled` sin publicacion
previa tarda ese tiempo en llegar a la DLQ. Detalle y ajustes: ADR 0008.

### Otras

- Pagos y reembolsos reales fuera del alcance: la pasarela es siempre simulada (`tok_declined` rechaza).
- Reembolsos parciales, asientos numerados y multi-moneda en una misma reserva: fuera del alcance.
- La DLQ requiere operacion humana; no hay reproceso automatico.
