# catalog-service

Catalogo de eventos y zonas de entradas de EventHub: Spring MVC + JPA + PostgreSQL, arquitectura
hexagonal, API contract-first (`contracts/openapi/catalog-api.yaml`) y eventos `EventPublished`,
`EventCancelled` y `EventRescheduled` hacia RabbitMQ mediante un **outbox transaccional**.

Decisiones: [`docs/adr`](../../docs/adr) (hexagonal y outbox, Spring Boot 4, bootstrap de eventos,
versionado). Auditoria contra CLAUDE.md: [`docs/audit-claude-md.md`](../../docs/audit-claude-md.md).

## Requisitos

Java 21 y Docker (Compose v2). El resto lo trae el Maven Wrapper (`./mvnw`).
Los comandos son de bash (Linux, macOS, WSL o Git Bash) y se lanzan **desde la raiz del repositorio**.

## Levantar el entorno

```bash
cp .env.example .env          # opcional: compose usa estos mismos valores por defecto
docker compose up -d          # postgres, rabbitmq (con management) y keycloak con el realm `eventhub`
docker compose ps             # espera a que los tres esten "healthy" (keycloak tarda ~40 s)
```

Arrancar el servicio (perfil `local`; las contrasenas no tienen valor por defecto a proposito):

```bash
export POSTGRES_PASSWORD=eventhub_dev RABBITMQ_DEFAULT_PASS=eventhub_dev   # valores de .env.example
./mvnw -pl services/catalog-service spring-boot:run -Dspring-boot.run.profiles=local
```

Escucha en `http://localhost:8081`. Flyway crea el esquema en la base `catalog`.

| Variable | Para que | Por defecto |
|---|---|---|
| `POSTGRES_PASSWORD` | contrasena de PostgreSQL (perfil `local`) | **ninguno** |
| `RABBITMQ_DEFAULT_PASS` | contrasena de RabbitMQ (perfil `local`) | **ninguno** |
| `OIDC_ISSUER_URI` | emisor de los JWT (`spring.security.oauth2.resourceserver.jwt.issuer-uri`) | `http://localhost:8080/realms/eventhub` en `local`; ninguno fuera |
| `POSTGRES_USER`, `POSTGRES_DB`, `POSTGRES_PORT` | conexion | `eventhub`, `catalog`, `5432` |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_HOST`, `RABBITMQ_PORT` | conexion | `eventhub`, `localhost`, `5672` |
| `KEYCLOAK_PORT` | puerto de Keycloak | `8080` |

Fuera del perfil `local` hay que dar `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_USER`,
`RABBITMQ_PASSWORD` y `OIDC_ISSUER_URI`. Nada de esto se versiona.

Ajustes del outbox (`eventhub.outbox.*`): `enabled`, `batch-size` (50), `poll-interval` (1s),
`max-attempts` (10), `initial-backoff` (2s), `max-backoff` (5m), `confirm-timeout` (5s).

## Tests

```bash
./mvnw verify                                   # todo: unitarios, integracion (Testcontainers), ArchUnit, cobertura
./mvnw -pl services/catalog-service test        # solo unitarios, sin Docker
./mvnw -pl services/catalog-service verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
       -Dit.test=OutboxRelayIT                  # un IT concreto
```

Los `*IT` usan PostgreSQL y RabbitMQ reales con Testcontainers, asi que necesitan Docker en marcha.
Cobertura: el build **falla** si el dominio queda por debajo del 90 % de lineas; la aplicacion (objetivo
80 %) solo se informa. Informe en `services/catalog-service/target/site/jacoco/index.html`.

## Probar la API con curl

Usuarios del realm (solo desarrollo): `organizer` / `organizer_dev` (rol `ORGANIZER`),
`attendee` / `attendee_dev` (sin rol) y `admin` / `admin_dev` (rol `ADMIN`, solo para la reemision de
eventos). Client publico: `eventhub-web`.

### 1. Token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/realms/eventhub/protocol/openid-connect/token \
  -d grant_type=password -d client_id=eventhub-web -d username=organizer -d password=organizer_dev \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
echo "${TOKEN:0:20}..."
```

### 2. Preparar una cola para ver los mensajes

El servicio no declara colas: cada consumidor declara la suya. Para mirar los mensajes, crea una cola de
prueba enlazada al exchange (el exchange se crea idempotentemente con las mismas propiedades que usa
el servicio). **Hazlo antes de publicar**: sin cola enlazada, RabbitMQ descarta el mensaje (ver
"Eventos no enrutables").

```bash
RMQ="-s -u eventhub:eventhub_dev -H content-type:application/json http://localhost:15672/api"
curl $RMQ/exchanges/%2F/eventhub.events -X PUT -d '{"type":"topic","durable":true}'
curl $RMQ/queues/%2F/demo.events -X PUT -d '{"durable":true}'
curl $RMQ/bindings/%2F/e/eventhub.events/q/demo.events -X POST -d '{"routing_key":"catalog.event.*.v1"}'
```

### 3. Crear, anadir zona y publicar

```bash
API=http://localhost:8081/api/v1
AUTH="Authorization: Bearer $TOKEN"
JSON="Content-Type: application/json"
CORR="X-Correlation-Id: 6f0c5d1e-2b7a-4c1f-9d3e-5a8b7c6d4e21"      # opcional; si falta se genera uno
STARTS=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)                 # macOS: date -u -v+30d +%Y-%m-%dT%H:%M:%SZ
ENDS=$(date -u -d '+30 days 4 hours' +%Y-%m-%dT%H:%M:%SZ)           # macOS: date -u -v+30d -v+4H ...

# crear (queda en DRAFT; el organizerId sale del token)
EVENT_ID=$(curl -s -X POST $API/events -H "$AUTH" -H "$JSON" -H "$CORR" -d '{
  "title":"Rock Fest","description":"Open air","venue":"Estadio Nacional",
  "startsAt":"'$STARTS'","endsAt":"'$ENDS'"}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "event: $EVENT_ID"

# anadir una zona (importes como texto con 2 decimales)
curl -s -X POST $API/events/$EVENT_ID/zones -H "$AUTH" -H "$JSON" \
  -d '{"name":"General","price":{"amount":"150.00","currency":"PEN"},"capacity":500}'

# publicar: DRAFT -> PUBLISHED; guarda un EventPublished en el outbox en la misma transaccion
curl -s -X POST $API/events/$EVENT_ID/publish -H "$AUTH" -H "$CORR"
```

### 4. Ver el mensaje

El relay corre cada segundo; espera un instante y lee la cola:

```bash
sleep 3
curl $RMQ/queues/%2F/demo.events/get -X POST \
  -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}'
```

Veras `"routing_key":"catalog.event.published.v1"` y un envelope v1 en `payload`
(`messageId`, `type`, `version`, `occurredAt`, `correlationId` = el que enviaste, `producer`, `data`).

### 5. Otras operaciones

```bash
curl -s "$API/events?size=5&sort=-startsAt"                         # publico: solo PUBLISHED
curl -s "$API/events?mine=true" -H "$AUTH"                          # mis eventos, en cualquier estado
curl -s $API/events/$EVENT_ID                                       # publico si esta PUBLISHED
curl -s -X POST $API/events/$EVENT_ID/reschedule -H "$AUTH" -H "$JSON" \
  -d '{"startsAt":"'$(date -u -d '+45 days' +%Y-%m-%dT%H:%M:%SZ)'","endsAt":"'$(date -u -d '+45 days 3 hours' +%Y-%m-%dT%H:%M:%SZ)'"}'
curl -s -X POST $API/events/$EVENT_ID/cancel -H "$AUTH" -H "$JSON" -d '{"reason":"Venue unavailable"}'
```

### Errores

Todos los errores son `application/problem+json` (RFC 9457), nunca con trazas. Incluyen `traceId`.

| Codigo | Cuando | `type` (`https://eventhub.dev/problems/...`) |
|---|---|---|
| 400 | campo invalido, cuerpo mal formado, id o parametro con formato erroneo | `validation-error` (con `errors[]`) |
| 401 | falta el token o no es valido | `unauthorized` |
| 403 | falta el rol `ORGANIZER`, o el evento visible es de otro organizador | `forbidden` |
| 404 | no existe, o es un evento no publicado de otro organizador | `not-found` |
| 409 | transicion de estado invalida; modificacion concurrente | `invalid-state`, `concurrent-modification` |
| 422 | regla de negocio incumplida | `event-without-zones`, `starts-at-not-in-future`, `event-already-started`, `zone-limit-exceeded`, `duplicate-zone-name` |
| 500 | error inesperado (sin detalles internos) | `internal-error` |

## Flujo del outbox

```mermaid
sequenceDiagram
    participant C as Cliente
    participant API as catalog-service (API)
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant MQ as RabbitMQ (eventhub.events)
    participant K as Consumidor (cola propia)

    C->>API: POST /events/{id}/publish
    API->>DB: UPDATE event + INSERT outbox_event (UNA transaccion)
    API-->>C: 200
    loop cada poll-interval
        R->>DB: SELECT ... FOR UPDATE SKIP LOCKED (filas vencidas, orden por agregado)
        R->>MQ: publicar (routing key catalog.event.published.v1)
        MQ-->>R: confirm (ack)
        R->>DB: UPDATE published_at (commit)
    end
    MQ->>K: entrega at-least-once
    Note over R,DB: sin ack -> attempts+1, last_error, next_attempt_at (backoff);<br/>agotados los intentos la fila queda aparcada
```

Garantias: el cambio y su evento se guardan juntos o ninguno; `messageId` es el id de la fila y no cambia
al reintentar; la entrega es **at-least-once** (los consumidores deben ser idempotentes); el orden es **por
agregado**: un evento no se envia mientras haya otro anterior del mismo agregado sin publicar.

## Reemitir eventos

Cuando un consumidor nuevo (o con la cola recreada) debe ponerse al dia, el catalogo puede volver a escribir
en el outbox el **estado actual** de sus eventos. Es una operacion administrativa: `POST /actuator/replay`,
solo para el rol **`ADMIN`** (un `ORGANIZER` recibe 403) y fuera de la API publica y del OpenAPI.
**Antes**, el consumidor debe tener su cola declarada y enlazada.

| Estado del evento | Que se reemite |
|---|---|
| `PUBLISHED` | `EventPublished` con las fechas, el venue y las zonas vigentes |
| `CANCELLED` | `EventPublished` y despues `EventCancelled` |
| `DRAFT` | nada |

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/realms/eventhub/protocol/openid-connect/token \
  -d grant_type=password -d client_id=eventhub-web -d username=admin -d password=admin_dev \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

# todo el catalogo, en lotes de 100 (limit opcional, maximo 500)
curl -s -X POST localhost:8081/actuator/replay -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{}'
# {"events":3,"messages":4,"conflicts":0}

# un solo evento
curl -s -X POST localhost:8081/actuator/replay -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"eventId":"'$EVENT_ID'"}'
```

Para recorrer un catalogo grande por lotes, repite la llamada con el cursor que devuelve la anterior:

```bash
curl -s -X POST localhost:8081/actuator/replay -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"limit":2}'
# {"events":2,"messages":2,"conflicts":0,"next":"e5ef40db-13a5-48f4-b78d-561cfbeb21c4"}
curl -s -X POST localhost:8081/actuator/replay -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"limit":2,"after":"e5ef40db-13a5-48f4-b78d-561cfbeb21c4"}'
# {"events":1,"messages":2,"conflicts":0}        <- sin "next": no queda nada
```

- La respuesta cuenta `events` (reemitidos), `messages` (filas de outbox escritas), `conflicts` (eventos que
  alguien modifico justo en ese momento: se saltan y basta repetir la llamada) y `next` (solo si puede quedar mas).
- Cada reemision usa `messageId` **nuevos**, el mismo `eventId` y pasa por el relay normal (orden por agregado,
  reintentos, aparcado); el consumidor la distingue de un duplicado por su `eventId`, no por el `messageId`.
- `occurredAt` es la fecha del ultimo cambio del evento (la publicacion original no se guarda). La reemision
  aumenta la `version` del evento en 1 y no cambia nada mas. Repetirla es seguro: envia lo mismo otra vez.
- Errores: sin token 401, sin rol `ADMIN` 403, `limit` fuera de 1..500 o `eventId` mal formado 400, evento
  inexistente 404 (un borrador devuelve `events: 0`).

## Operacion

- `GET /actuator/health/liveness` y `/readiness` (publicos), `GET /actuator/info`.
- `GET /actuator/prometheus` **requiere token** (cualquier usuario autenticado). Metricas del outbox:
  `outbox_pending` (filas que aun se reintentan), `outbox_parked` (sin intentos), `outbox_oldest_age_seconds`,
  `outbox_published_total`, `outbox_failures_total` y `outbox_parkings_total` (veces que una fila se aparco).
  ```bash
  curl -s localhost:8081/actuator/prometheus -H "$AUTH" | grep '^outbox_'
  ```
- Logs JSON por consola con `correlationId` y `traceId`. El tracing no exporta nada hasta que se activa
  un exportador OTLP con su endpoint (`management.opentelemetry.tracing.export.otlp.endpoint` y
  `management.tracing.export.otlp.enabled=true`).

### Runbook: filas aparcadas del outbox

Una fila se **aparca** al llegar a `max-attempts` fallos. Deja de reintentarse y **bloquea los eventos
posteriores de su mismo agregado** (otros agregados siguen). Senales: `outbox_parked > 0`, un
`outbox_oldest_age_seconds` que crece, o logs `ERROR Outbox row ... is parked`.

1. **Listar** las aparcadas (ajusta `10` si cambiaste `max-attempts`):
   ```bash
   docker compose exec postgres psql -U eventhub -d catalog -c \
    "SELECT id, aggregate_id, event_type, attempts, last_error, created_at, next_attempt_at
       FROM outbox_event
      WHERE published_at IS NULL AND attempts >= 10
      ORDER BY created_at"
   ```
2. **Diagnosticar** con `last_error`: broker caido, credenciales, exchange inexistente, timeout... Arregla
   la causa **antes** de reencolar, o volvera a fallar.
3. **Reencolar** (la fila vuelve a ser elegible de inmediato; el orden por agregado se respeta solo):
   ```bash
   docker compose exec postgres psql -U eventhub -d catalog -c \
    "UPDATE outbox_event
        SET attempts = 0, next_attempt_at = now(), last_error = NULL
      WHERE published_at IS NULL AND attempts >= 10"
   ```
   Para una sola: anade `AND id = '<uuid>'`. Reencolar todas las de un agregado en orden es seguro.
4. **Comprobar**: `outbox_parked` vuelve a 0, `published_at` se rellena y el mensaje llega a la cola.
5. **Descartar** una fila (caso excepcional) solo si se decide que ese evento no debe llegar nunca:
   `UPDATE outbox_event SET published_at = now(), last_error = 'discarded manually: <motivo>' WHERE id = ...`.
   Los consumidores tendran un hueco en la historia de ese agregado: anotalo en el incidente.

Un fallo aislado no necesita intervencion: el relay reintenta con backoff exponencial (2 s, 4 s, 8 s...
hasta `max-backoff`) y se recupera solo cuando el broker vuelve.

### Eventos no enrutables

El exchange es `topic`. RabbitMQ **confirma** un mensaje cuando lo acepta y lo enruta, pero si **ninguna
cola esta enlazada** a la routing key lo descarta y aun asi confirma. Por eso:

- El servicio marca la fila como publicada aunque nadie la haya recibido, y **no existe una metrica de
  mensajes no enrutables** (el binder de Spring Cloud Stream no expone los mensajes devueltos).
- Cada consumidor debe declarar su cola y su enlace **al arrancar**. Un consumidor nuevo no recibe lo que
  se publico antes de existir; para ponerlo al dia se **reemiten** los eventos (ver "Reemitir eventos",
  [ADR 0003](../../docs/adr/0003-event-bootstrap-and-replay.md)), y el consumidor debe ser idempotente por `eventId`.
- Si tras publicar no ves el mensaje, comprueba primero que tu cola esta enlazada
  (`curl $RMQ/bindings/%2F/e/eventhub.events/q/demo.events`).

## Apagar

```bash
docker compose down        # conserva los datos
docker compose down -v     # borra tambien los volumenes (postgres, rabbitmq)
```
