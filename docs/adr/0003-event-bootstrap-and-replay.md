# 0003. Bootstrap y reemision de `EventPublished`

- Estado: **implementado** (M8, rama `feat/catalog-replay`)
- Fecha: 2026-11-24 (decision) · implementado el 2026-11-25

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
3. **Reemision administrativa de los eventos** desde el catalogo, con consumidores idempotentes por el
   id de negocio del evento.
4. Colas de tipo stream con relectura. Resuelve el replay, pero cambia el modelo de mensajeria de todos
   los servicios; fuera del alcance del MVP.

## Decision

Opcion 3, en tres reglas:

1. **El consumidor declara su cola al arrancar** (cola durable, enlace a `catalog.event.*.v1`, DLQ y
   reintentos con backoff) **antes** de pedir una reemision. Es parte de su arranque.
2. **El catalogo ofrece una reemision administrativa** que escribe en el outbox el **estado actual** de
   los eventos publicados y cancelados (detalle abajo). Pasa por el mismo relay que cualquier evento:
   mismo orden por agregado, confirmaciones, reintentos y aparcado.
3. **Los consumidores son idempotentes por el `eventId` de negocio**, no solo por `messageId`. Una
   reemision tiene un `messageId` nuevo (es otra fila de outbox) pero el mismo `eventId`; el consumidor
   crea el inventario con "insertar si no existe" sobre `eventId`, y trata un `EventPublished` repetido
   como una actualizacion o un no-op, nunca como un duplicado.

## Implementacion

**Disparo.** `POST /actuator/replay`, un endpoint de Actuator propio (`ReplayEndpoint`): no esta en la
API publica ni en `contracts/openapi`. Exige el rol **`ADMIN`**, distinto de `ORGANIZER` (un `ADMIN` sin
`ORGANIZER` recibe 403 en `/api/v1`; un `ORGANIZER` recibe 403 aqui). Cuerpo JSON, todo opcional:

| Campo | Significado |
|---|---|
| `eventId` | reemite solo ese evento (404 si no existe; un borrador devuelve 0) |
| `limit` | eventos como maximo por llamada: 1..500, 100 por defecto (fuera de rango: 400) |
| `after` | cursor: solo eventos con id mayor (orden por id; se ignora con `eventId`) |

Respuesta: `{"events": n, "messages": m, "conflicts": c, "next": "<uuid>"}`. `next` solo aparece si puede
quedar algo por reemitir: se repite la llamada con `after=<next>` hasta que falte.

**Que se reemite (el estado actual, no el historial).**

| Estado | Mensajes |
|---|---|
| `PUBLISHED` | `EventPublished` con las fechas, el venue y las zonas **vigentes** (tras reprogramaciones) |
| `CANCELLED` | `EventPublished` y despues `EventCancelled` (con su motivo) |
| `DRAFT` | nunca: no ha ocurrido todavia |

`occurredAt` de esos mensajes es el `updated_at` del evento: el instante de su **ultimo cambio**. La
publicacion original no se guarda, asi que no es la hora exacta del primer `publish`; para un evento
cancelado es la hora real de la cancelacion (el `cancelledAt` del contrato). No se reemite
`EventRescheduled`: el estado vigente ya viaja en `EventPublished`.

**Cada reemision es una escritura del agregado.** Para cada evento, en su propia transaccion, se guarda el
aggregate (reclama la fila y sube la version en 1, ADR 0004) y se escriben las filas de outbox. Asi, un
cambio concurrente (por ejemplo una cancelacion) o ocurre antes y se reemite, o falla con conflicto: el
outbox nunca queda con un `EventPublished` viejo detras del `EventCancelled` real. Un conflicto no aborta
el lote: se cuenta en `conflicts` y se repite la llamada. El resto del estado del evento no cambia.

**Orden.** El publicador del outbox sella `created_at` **estrictamente creciente** dentro de una llamada,
de modo que `EventPublished` y `EventCancelled` de un mismo evento no pueden empatar y que el desempate
por el `id` aleatorio los invierta. Las filas de la reemision van detras de las que el evento ya tuviera
pendientes (orden por agregado).

## Consecuencias

- Un consumidor nuevo se pone al dia sin acoplar el catalogo a sus colas ni abrir una llamada sincrona.
- Todos los consumidores de `EventPublished` deben implementar la idempotencia por `eventId`, ademas de
  `processed_message(message_id)`. Debe constar en los contratos de cada consumidor y probarse con un
  test de duplicado con distinto `messageId` y mismo `eventId`.
- La reemision tambien llega a los consumidores ya al dia; por eso la idempotencia no es opcional. Quien
  la lance debe hacerlo con la cola del consumidor ya enlazada.
- Cada reemision incrementa la `version` del evento (efecto visible, sin otro cambio de estado). Un
  organizador que edite justo mientras se reemite puede recibir un 409 y debe reintentar.
- No hay una operacion de reemision en la API publica ni en el OpenAPI, y el rol `ADMIN` hay que
  asignarlo en el proveedor de identidad (en desarrollo, el usuario `admin` del realm de Keycloak).
- Sigue sin existir una metrica de mensajes no enrutables (limite del binder): reemitir no avisa si
  la cola del consumidor no estaba enlazada.

## Evidencia

`EventReplayTest` (dominio), `EventReplayServiceTest` (aplicacion), las pruebas de contrato de
`findReplayable` (fake y JPA) y `ReplayEndpointIT` (PostgreSQL y RabbitMQ reales): estados mixtos, orden de
mensajes, borrador excluido, `messageId` nuevos, payloads validados contra los JSON Schemas, 401/403,
idempotencia, paginacion, conflictos y propagacion del `correlationId`. Una mutacion que reemite
borradores hace fallar 9 tests en 4 clases.
