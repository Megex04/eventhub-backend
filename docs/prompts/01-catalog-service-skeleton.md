# Prompt 01: esqueleto de `catalog-service`

> Uso: desde la raiz del repo, ejecuta `claude`, activa el **modo plan** (Shift+Tab) y pega el bloque de abajo.
> Revisa el plan, aprueba y deja que avance **hito por hito**, validando cada uno antes de seguir.

---

```text
Lee primero CLAUDE.md completo y los contratos en contracts/openapi/catalog-api.yaml y
contracts/events/*.json. Son la fuente de verdad: si algo de lo que te pido los contradice, avisame
antes de implementar.

OBJETIVO
Crear el esqueleto funcional y bien testeado de `catalog-service` (Spring MVC + JPA + PostgreSQL),
con arquitectura hexagonal, contract-first, outbox transaccional y eventos EventPublished /
EventCancelled / EventRescheduled hacia RabbitMQ.

PRIMERO: entra en modo plan. Presenta un plan por hitos (M1..M7 de abajo, ajustando lo que
consideres necesario), lista las dependencias que vas a agregar con su justificacion y las
preguntas abiertas. No escribas codigo hasta que yo apruebe.

HITOS (un commit Conventional por hito; `./mvnw verify` en verde al terminar cada uno)

M1. Build y entorno local
- pom.xml parent (packaging pom) + modulo services/catalog-service. Maven Wrapper.
  Versiones de Spring Boot y Spring Cloud verificadas y compatibles con Java 21.
- docker-compose.yml en la raiz: postgres (con BD `catalog`), rabbitmq (con management) y keycloak
  con un realm importado: rol ORGANIZER, un usuario organizador y un usuario normal de prueba,
  y un client publico para pruebas. Credenciales solo de desarrollo, por variables o archivo .env.example.
- Perfil `local` en application.yml. Actuator health. Formato (spotless o checkstyle).

M2. Dominio (TDD, sin Spring)
- Aggregate `Event` (id, organizerId, title, description, venue, startsAt, endsAt, status, zones,
  cancelReason, createdAt, updatedAt), entidad `TicketZone`, value object `Money` (record),
  enum `EventStatus` (DRAFT, PUBLISHED, CANCELLED, FINISHED).
- Comportamiento: create, update (solo DRAFT), addZone/updateZone/removeZone (solo DRAFT),
  publish, cancel(reason), reschedule(startsAt, endsAt, venue?).
- Invariantes y errores segun la descripcion del OpenAPI: publicar exige >=1 zona, startsAt futuro
  (usar Clock inyectado) y endsAt > startsAt; CANCELLED es terminal; zonas inmutables tras publicar;
  maximo 20 zonas; nombres de zona unicos dentro del evento.
- Excepciones de dominio propias distinguiendo "estado invalido" (-> 409) de "regla de negocio" (-> 422).
- Domain events como sealed interface + records (EventPublished, EventCancelled, EventRescheduled)
  que el aggregate registra internamente y expone para ser publicados.
- Tests unitarios exhaustivos (incluye casos limite de fechas y Money).

M3. Capa de aplicacion
- Puertos de entrada (casos de uso) y de salida (EventRepository, DomainEventPublisher/OutboxPort,
  Clock) segun CLAUDE.md. Implementaciones en application/service con @Transactional.
- Casos de uso: Create, Update, Delete, AddZone, UpdateZone, RemoveZone, Publish, Cancel,
  Reschedule, Get, List (filtros del OpenAPI: status, q, from, to, mine, paginacion, orden).
- Verificar propiedad del recurso (organizerId == usuario) y visibilidad (no PUBLISHED solo para su duenio).
- Tests con Mockito/fakes en memoria de los puertos.

M4. Adaptador de persistencia
- Migraciones Flyway: tablas event, ticket_zone, outbox_event, con constraints e indices justificados.
- Entidades JPA separadas del dominio + mappers + repositorio que implementa el puerto.
- Consulta de listado con filtros y paginacion sin N+1.
- Tests de integracion con Testcontainers (PostgreSQL real): CRUD, filtros, restricciones de unicidad, concurrencia basica.

M5. Adaptador web + seguridad
- Generar interfaces desde contracts/openapi/catalog-api.yaml con openapi-generator-maven-plugin
  y hacer que los controllers las implementen.
- GlobalExceptionHandler con ProblemDetail (RFC 9457) y el mapeo de codigos de CLAUDE.md.
- Resource Server JWT (Keycloak local), mapeo de roles a ROLE_ORGANIZER, rutas publicas segun OpenAPI.
- organizerId desde el claim `sub`.
- Tests MockMvc: happy path de cada endpoint, validaciones (400), estados invalidos (409),
  reglas de negocio (422), sin token (401), rol/duenio incorrecto (403), no encontrado (404).

M6. Mensajeria con outbox
- Al publicar/cancelar/reprogramar, guardar el evento en outbox_event en la MISMA transaccion.
- Publicador (poller con FOR UPDATE SKIP LOCKED) que envia al exchange `eventhub.events` con la
  routing key correcta, usando Spring Cloud Stream. Envelope v1 con messageId, correlationId
  (propagado desde el request o generado), producer=catalog-service.
- Tests de contrato: cada payload producido valida contra su JSON Schema en contracts/events/.
- Test de integracion con Testcontainers (Postgres + RabbitMQ): publicar un evento via API -> el mensaje llega a una cola de prueba.
- Test de resiliencia: si el broker esta caido, el outbox conserva el mensaje y se reintenta.

M7. Calidad y documentacion
- ArchUnit: reglas de hexagonal de CLAUDE.md (domain sin frameworks, application no depende de infrastructure, etc.).
- JaCoCo con objetivos de CLAUDE.md (reporte; que falle el build solo en dominio < 90 %).
- services/catalog-service/README.md: como levantar el entorno, correr tests, ejemplos curl
  (obtener token en Keycloak, crear, publicar) y diagrama simple del flujo outbox.
- docs/adr/0001-hexagonal-and-outbox.md (formato MADR corto) con el por que de estas decisiones.

RESTRICCIONES
- No implementes booking-service ni notification-service.
- No agregues funcionalidades fuera de los contratos (sin pagos, sin asientos numerados).
- No modifiques los archivos de contracts/ sin avisarme y explicar el motivo.
- Si encuentras una incoherencia en los contratos, detente y preguntame.

CRITERIOS DE ACEPTACION
- `docker compose up -d && ./mvnw verify` termina en verde desde cero.
- Todos los endpoints del OpenAPI estan implementados y cubiertos por tests.
- Los payloads de eventos validan contra los JSON Schemas.
- ArchUnit en verde; cobertura de dominio >= 90 %.
- Sin secretos versionados; README reproducible.

Al finalizar cada hito, dame un resumen de 3-5 lineas: que hiciste, que decisiones tomaste y que
sigue. Empieza con el plan.
```

---

## Prompts de seguimiento utiles

```text
Haz un code review estricto de lo implementado en el hito actual contra CLAUDE.md:
violaciones de hexagonal, invariantes faltantes, tests debiles, riesgos de concurrencia y de seguridad.
Lista hallazgos por severidad; no cambies codigo todavia.
```

```text
Agrega un test que demuestre que dos peticiones concurrentes de publish sobre el mismo evento
dejan UN solo EventPublished en el outbox. Si falla, propon el fix minimo.
```

```text
Genera el ADR 0005 explicando por que el inventario de booking-service se mantiene como
proyeccion local y no mediante llamadas sincronas a catalog-service.
```
