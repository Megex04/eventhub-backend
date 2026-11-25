# Auditoria de `catalog-service` contra CLAUDE.md

Fecha: 2026-11-24 (actualizada en M8, 2026-11-25) · Estado del build: `./mvnw verify` en verde (ver "Resultado") · Alcance: M1-M8 de
`catalog-service` (los otros servicios estan fuera de alcance del prompt 01).

Leyenda: **Cumple** = hay test, regla ArchUnit o configuracion que lo demuestra. **Parcial** = se
cumple pero la evidencia es manual o incompleta. **Desviacion** = no se cumple o se cumple distinto, con
justificacion. **N/A** = no aplica todavia a este servicio.

## Flujo de trabajo

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| 1. Plan primero y esperar aprobacion | Parcial | Cada hito (M5, M6, M7, el endurecimiento M5.1 y los ADR) se planifico y se aprobo en la sesion antes de escribir codigo. No queda rastro en el repositorio; es proceso. |
| 2. Hitos pequenos, `verify` en verde y un commit | Cumple | `git log`: un commit Conventional por hito logico, cada uno con `./mvnw verify` en verde. |
| 3. TDD en dominio y casos de uso | Parcial | En el endurecimiento de M5.1 los tests de concurrencia y de 409 se vieron fallar (deadlock, lost update, 500) antes de arreglar el codigo; los de mensajeria de M6 se escribieron junto con la implementacion. El historial junta test e implementacion en el mismo commit, asi que el orden no es verificable ahi. La efectividad de los tests se demuestra con **mutaciones** (quitar el incremento de version, el orden por agregado, el bloqueo de filas, el corte de lote, cada regla ArchUnit). |
| 4. Preguntar ante ambiguedad o contradiccion | Cumple | Se pregunto: el 401 de `listEvents`, los codigos 422/400/401 no declarados, el conflicto networknt 2.x/3.x, la numeracion de ADR, tracing y Prometheus. |
| 5. Sin dependencias fuera de alcance; justificar cada una | Cumple | Cada dependencia lleva su comentario en `pom.xml`. Nuevas en M5-M7: resource server, validation, openapi-generator, Spring Cloud Stream Rabbit, OpenTelemetry (tracing), Prometheus, y de test: ArchUnit, openapi-request-validator, Testcontainers RabbitMQ, starter de test de OpenTelemetry. |
| 6. Nunca commitear secretos | Cumple | `git ls-files` solo contiene `.env.example`; `.gitignore` excluye `.env`. `git grep` de `password/secret/token` solo encuentra placeholders (`${...}`), el ejemplo de `.env.example`, los defaults de desarrollo de `docker-compose.yml` y el realm de Keycloak de desarrollo (credenciales de prueba, permitido por el prompt M1). Las contrasenas de `application*.yml` no tienen valor por defecto. |

## Stack

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| Java 21 (records, sealed, patrones, virtual threads) | Cumple | `java.version=21`; `Money` es `record`; `DomainEvent` es `sealed interface ... permits`; `switch` con patrones en `OutboxEnvelopeFactory` y `Viewer`; `spring.threads.virtual.enabled=true`. |
| Spring Boot ultima estable, fijada en el parent | Cumple | Boot 4.2.0 en `pom.xml`; `docs/adr/0002-spring-boot-4.md`. |
| Maven Wrapper y versiones en el parent | Cumple | `mvnw`; `dependencyManagement` y `pluginManagement` del parent. |
| PostgreSQL + Flyway | Cumple | `V1__create_event_tables.sql`, `V2__outbox_relay_state.sql`; `SchemaConstraintsIT`. |
| RabbitMQ local; Azure Service Bus en Azure | Parcial | RabbitMQ: `OutboxRelayIT`. Azure Service Bus es fase 2 (N/A); la abstraccion es Spring Cloud Stream, que lo permite cambiando de binder. |
| Mensajeria via Spring Cloud Stream | Cumple | `StreamBridgeOutboxBroker` (binding `outbox-out-0`). |
| Auth: resource server JWT | Cumple | `SecurityConfig`; `SecurityIT`. Keycloak: `docker/keycloak/eventhub-realm.json`. Entra ID es fase 3. |
| Tests: JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit, MockMvc/WebTestClient, networknt | **Desviacion** | **Mockito no se usa**: los tests usan fakes en memoria (`InMemoryEventRepository`...) con un test de contrato compartido con el adaptador real. **WebTestClient** no aplica (servicio MVC, no WebFlux). **networknt** es la 2.0.1 y no la 3.x: lo impone `openapi-request-validator` (ADR 0001, "Deuda tecnica"); Jackson 2 solo en el classpath de test. El resto, cumple. |

## Estructura del monorepo

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| `contracts/`, `docs/prompts`, `docs/adr`, `docker-compose.yml`, `services/catalog-service` | Cumple | Presentes. |
| `booking-service` y `notification-service` | N/A | Fuera del alcance del prompt 01. |

## Arquitectura hexagonal

| Regla | Estado | Evidencia |
|---|---|---|
| `domain` solo JDK | Cumple | ArchUnit `domain_is_plain_java`. |
| `application` solo depende de `domain` y sus puertos; sin web ni persistencia | Cumple | `layers_only_depend_inwards`, `application_uses_no_logging_web_persistence_or_json`, `use_case_implementations_only_know_the_domain_and_their_ports`, `ports_are_interfaces_or_records`. |
| `infrastructure` depende hacia dentro, nunca al reves | Cumple | `layers_only_depend_inwards`. |
| Entidades JPA distintas de los aggregates, mapeo explicito | Cumple | `jpa_entities_live_only_in_the_persistence_adapter`, `nothing_but_persistence_uses_jpa_entities`; mappers manuales (`EventEntityMapper`, `EventWebMapper`) con `EventEntityMapperTest`, `EventWebMapperTest`. |
| Controllers sin logica de negocio | Parcial | ArchUnit: `controllers_use_ports_and_generated_interfaces_not_other_adapters`, `controllers_implement_a_generated_contract_interface`. Que no hagan logica es revision de codigo (solo llaman a `EventWebMapper` y al caso de uso), sin test automatico. |
| Inyeccion por constructor, sin `@Autowired` en campos | Cumple | `no_field_injection`. |

## Modelado de dominio

| Regla | Estado | Evidencia |
|---|---|---|
| Aggregate protege invariantes, sin setters publicos | Cumple | `aggregates_have_no_public_setters`; `Event*Test`. |
| Value objects como `record` con validacion en el constructor | Cumple | `Money` (`MoneyTest`). |
| Dinero `BigDecimal` escala 2 `HALF_EVEN`, nunca `double`; string en JSON | Cumple | `MoneyTest`; patron `^\d{1,10}\.\d{2}$` en el OpenAPI; `EventWebMapperTest`, `OpenApiConformanceIT`. |
| `Clock` inyectado; sin `Instant.now()` en dominio/aplicacion; UTC | Cumple | `time_comes_from_an_injected_clock_in_domain_and_application`; `ClockConfig` y `ClockConfigTest`; `Instant` en todo el modelo. |
| Ids `UUID` generados en la aplicacion | Cumple | puerto `IdGenerator`, `UuidV4IdGenerator` (v4: no hay libreria de v7 adoptada). |
| Excepciones de dominio propias mapeadas en el adapter web | Cumple | `GlobalExceptionHandlerTest`. |
| Eventos de dominio como `sealed interface` + `record` | Cumple | `DomainEvent`, `EventPublished`... |

## API REST

| Regla | Estado | Evidencia |
|---|---|---|
| Contract-first con openapi-generator, controllers implementan las interfaces | Cumple | plugin en `pom.xml`; `controllers_implement_a_generated_contract_interface`. |
| `/api/v1`, recursos en plural, paginacion `page`/`size` | Cumple | `EventsControllerIT` (paginacion y orden). |
| Errores RFC 9457 con un `@RestControllerAdvice` central, sin trazas | Cumple | `GlobalExceptionHandler`; `SecurityIT.should_not_expose_the_stack_trace_when_a_request_fails`; `OutboxAtomicityIT`. |
| Mapeo 400/409/422/404/403 | Cumple | `EventsControllerIT`, `ZonesControllerIT`, `LifecycleControllerIT`. |
| El OpenAPI declara todo lo que se devuelve | Cumple | `OpenApiConformanceIT` (11 operaciones, todos los codigos; falla con el contrato anterior). |

## Persistencia

| Regla | Estado | Evidencia |
|---|---|---|
| Esquema solo por Flyway, `ddl-auto=validate` | Cumple | `application.yml`; el contexto de los IT arranca con `validate`. |
| Una BD por servicio | Cumple | `catalog` (unico servicio hoy). |
| Evitar N+1, `open-in-view=false` | Cumple | `QueryCountIT`; `open-in-view: false`. |
| Indices justificados en la migracion | Cumple | comentarios en `V1` y `V2`; `SchemaConstraintsIT` (indices parciales). |
| Concurrencia optimista (ver ADR 0004) | Cumple | `VersioningIT`, `ZoneConcurrencyIT`, `PublishConcurrencyIT`, `ConcurrentModificationIT`, con mutaciones. |

## Mensajeria

| Regla | Estado | Evidencia |
|---|---|---|
| Esquemas en `contracts/events`, envelope v1 | Cumple | `EventSchemaContractTest` (con casos negativos). |
| Exchange `eventhub.events`, routing key `<svc>.<agg>.<evt>.v<n>` | Cumple | `RoutingKeysTest`, `OutboxRelayIT`. |
| Una cola por consumidor+tema con DLQ y reintentos | N/A | Las declara cada consumidor; el catalogo no consume. Pendiente en booking/notification. ADR 0003. |
| Outbox transaccional (misma transaccion, `SKIP LOCKED`, `published_at`) | Cumple | `OutboxAtomicityIT` (fallo del insert y del commit), `ConcurrentRelayIT`, `OutboxRelayIT`. |
| At-least-once | Cumple | confirmacion antes de marcar (`BrokerOutageIT`: mismo `messageId` al reenviar). |
| Consumidores idempotentes (`processed_message`) | N/A | El catalogo no consume. Pendiente en los consumidores; reemision y `eventId`: ADR 0003. |
| Cambios de esquema compatibles / `v2` | N/A | Solo existe v1. |
| Reemision administrativa del estado actual (ADR 0003, M8) | Cumple | `EventReplayTest`, `EventReplayServiceTest`, contrato de `findReplayable` (fake y JPA) y `ReplayEndpointIT`: estados mixtos, borrador excluido, orden, `messageId` nuevos, payloads validados contra los JSON Schemas, idempotencia, paginacion y conflictos. Mutacion que reemite borradores: 9 tests fallan. |
| Test de contrato de todo productor/consumidor | Parcial | Productor: cumple (`EventSchemaContractTest` y el IT que valida el mensaje entregado). Consumidores: N/A. |

## Seguridad

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| Lecturas publicas solo donde el OpenAPI lo indique | **Desviacion** | `GET /events` y `GET /events/{id}` (`security: []`) y ademas `/actuator/health/**` e `/actuator/info`, que **no** estan en el OpenAPI: son las sondas de liveness/readiness. `SecurityIT`. |
| Rol `ORGANIZER` desde `realm_access.roles` a `ROLE_*` | Cumple | `KeycloakRealmRolesConverterTest`, `SecurityIT`. |
| Operaciones administrativas fuera de la API publica y con un rol distinto (M8) | Cumple | `POST /actuator/replay` exige `ADMIN`, no `ORGANIZER`, y no esta en el OpenAPI (sigue en verde `OpenApiConformanceIT`). `ReplayEndpointIT`: 401 sin token, 403 con `ORGANIZER` y sin rol, y un `ADMIN` recibe 403 en `/api/v1`. |
| `organizerId`/`userId` del `sub`, nunca del body | Cumple | `EventsControllerIT.should_take_the_organizer_from_the_token_when_the_body_names_someone_else`. |
| Validar propiedad del recurso | Cumple | `EventOwnershipTest` y los IT (403 / 404 si no es visible). |
| Secretos por entorno, no en `application.yml` | Cumple | sin valores por defecto para contrasenas; ver regla 6. |
| Tests de seguridad 401/403/duenio | Cumple | `SecurityIT` (todas las operaciones), `*ControllerIT`. |

## Observabilidad

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| Logs JSON con `traceId` y `correlationId` | Cumple | `logging.structured.format.console: logstash`; `ObservabilityIT.should_log_json_with_the_trace_and_correlation_ids_when_a_request_fails` (mismo `traceId` en el log y en el problem). |
| Sin datos personales en logs | Parcial | Revision manual: hay 3 sentencias de log en el codigo de produccion (`GlobalExceptionHandler`, 2 en `OutboxRelay`) y solo registran metodo, URI, id de fila, tipo de evento y mensaje del fallo. Sin test automatico. |
| Actuator `health` (liveness/readiness) e `info` | Cumple | `CatalogServiceApplicationIT`, `SecurityIT`. |
| `prometheus`/OTLP via Micrometer; endpoints sensibles protegidos | Cumple (con matiz) | `/actuator/prometheus` exige token: `ObservabilityIT`. **OTLP esta incluido pero desactivado por defecto** (no hay colector; se activa por configuracion): decision registrada en el README. |
| Propagar `correlationId` request -> evento -> consumidores | Cumple | `X-Correlation-Id` -> MDC -> `CorrelationIdProvider` -> envelope (`OutboxRelayIT`, `CorrelationIdFilterTest`); descrito en el OpenAPI (`info.description`). El lado consumidor es de los otros servicios. |
| Metricas del outbox | Cumple | `OutboxRelayIT.should_report_the_backlog_and_its_age_when_rows_are_pending`, `ObservabilityIT`. |

## Testing

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| Piramide: unitarios > integracion > pocos E2E | Cumple | 389 unitarios, 238 de integracion (Testcontainers), 0 E2E dedicados: el flujo completo curl del README hace de prueba E2E manual. |
| Nombres `should_<resultado>_when_<condicion>`, un concepto por test | Cumple / Parcial | Nombres: `TestConventionsTest` (todos cumplen). "Un concepto por test" es revision. |
| Sin `Thread.sleep` (Awaitility) | Cumple | `TestConventionsTest.tests_never_sleep`; `BrokerOutageIT` usa Awaitility. |
| Cobertura dominio >= 90 %, aplicacion >= 80 % | Cumple | JaCoCo: el build **falla** bajo 90 % en dominio; aplicacion solo se informa. Medido (tras M8): dominio 100 %, aplicacion 99,5 %. |
| ArchUnit en cada servicio | Cumple | `ArchitectureTest` (17 reglas, incluida la del adaptador `adapter.in.admin`) y `TestConventionsTest`, con mutaciones. |

## Estilo y calidad

| Regla | Estado | Evidencia / justificacion |
|---|---|---|
| Metodos cortos, nombres con intencion, comentarios del "por que" | Parcial | Revision manual; no hay chequeo automatico de longitud de metodos. |
| `record`, `Optional` solo en retornos, colecciones inmutables, `switch` con patrones | **Desviacion** (parcial) | `Optional` aparece como **parametro** en `RescheduleEventUseCase.Command` (venue opcional: omitirlo conserva el actual) y en `EventSearchCriteria` (filtros opcionales). La alternativa seria `null`, que tambien esta prohibido. |
| Sin `System.out`, sin `catch (Exception)` generico | Cumple | `no_standard_streams`, `no_generic_catch`, `no_generic_exceptions_are_thrown`. El `@ExceptionHandler(Exception.class)` de `GlobalExceptionHandler` es el sitio previsto para lo inesperado. |
| Sin `null` como valor de negocio | Parcial | El aggregate guarda `cancelReason` como campo nulo interno y lo expone como `Optional`; los DTOs generados usan `null` para campos opcionales ausentes (lo exige el contrato). |
| Commits Conventional Commits | Cumple | `git log` (todos `feat/fix/test/docs/chore`), salvo el commit inicial `397a841 [Feat] Upload initial files`, anterior a esta sesion. |
| Formato con spotless; el build falla si hay violaciones | Cumple | `spotless:check` en la fase `verify`. |

## Comandos y fuera de alcance

| Regla | Estado | Evidencia |
|---|---|---|
| `docker compose up -d`, `./mvnw verify`, `spring-boot:run` con perfil `local` | Cumple | README; ejecutados desde un clon limpio (ver abajo). |
| Fuera de alcance: pagos, asientos, reembolsos, multi-moneda, multi-tenant | Cumple | No implementado. |
| Tras `PUBLISHED`, las zonas son inmutables | Cumple | `ZonesControllerIT` (409), `EventZonesTest`, `ZoneConcurrencyIT`. |

## Desviaciones y deuda abierta (resumen)

1. networknt 2.0.1 / Jackson 2 solo en test (ADR 0001); futuro modulo de test compartido de contratos.
2. Mockito sin uso (fakes); WebTestClient no aplica.
3. `/actuator/health` e `info` publicos y fuera del OpenAPI (sondas).
4. `Optional` como parametro en un comando y en el criterio de busqueda.
5. OTLP incluido pero desactivado por defecto.
6. Sin metrica de mensajes no enrutables (limite del binder). La reemision de `EventPublished` (ADR 0003)
   ya esta implementada (M8); falta la idempotencia por `eventId` en los consumidores.
7. DLQ, `processed_message` e idempotencia por `eventId`: pendientes en los consumidores.
8. Revisiones manuales sin chequeo automatico: controllers sin logica, longitud de metodos, datos
   personales en logs, "un concepto por test".

## Resultado de la prueba desde cero

Ejecutada el 2026-11-24 sobre el commit `7d3c464`, en una carpeta nueva (`git clone`), tras
`docker compose down -v` (volumenes borrados):

| Paso | Resultado |
|---|---|
| `cp .env.example .env` y `docker compose up -d --wait` | postgres, rabbitmq y keycloak *healthy* en ~25 s |
| `./mvnw verify` | **BUILD SUCCESS** en 1:31: 367 tests unitarios y 215 de integracion, ArchUnit, spotless y las dos puertas de cobertura |
| Arranque del servicio (perfil `local`, como en el README) | `readiness` UP a los ~15 s; 0 lineas `ERROR` en el log |
| Token de Keycloak (`organizer`), exchange, cola y enlace | 201 / 201 / 201 |
| Crear evento (`X-Correlation-Id` propio), anadir zona, publicar | 201 / 201 / 200 |
| Mensaje en la cola | `catalog.event.published.v1`, `correlation_id` = el enviado |
| Listados, `mine`, reprogramar, cancelar; 403 con `attendee`; 401 sin token | 200 / 200 / 200 / 200 / 403 / 401 |
| `/actuator/prometheus` sin token / con token | 401 / 200, con las 6 metricas `outbox_*` |
| Runbook de filas aparcadas | RabbitMQ pausado, evento cancelado (la API responde 200), fila llevada a 10 intentos con SQL, `outbox_parked` = 1, listada con la consulta del README, sigue aparcada al volver el broker, reencolada con el `UPDATE` del README: `outbox_parked` = 0 y el mensaje `catalog.event.cancelled.v1` llega |

Matices honestos: la fila "aparcada" se simulo poniendo `attempts = 10` por SQL (agotarla de verdad exige
10 fallos con backoff hasta 5 min; el comportamiento natural de aparcado esta probado en
`OutboxRelayIT`). Maven reutilizo el repositorio local `~/.m2` (no se aislo la cache de dependencias).

**Hallazgo de la prueba:** el gauge `outbox_parked` no aparecia en el scrape de Prometheus porque el
contador `outbox.parked.total` colisionaba con el en la misma familia de metricas. Los tests lo daban por
bueno al comparar subcadenas. Corregido en `7d3c464` (contador `outbox.parkings`) y `ObservabilityIT`
ahora exige los nombres completos de metrica.

### Verificacion de M8 (reemision)

2026-11-25, contra Docker real (`docker compose down -v`, realm con el rol `ADMIN` recien importado) y el
servicio en perfil `local`, **desde el repositorio de trabajo, no desde un clon limpio** (el clon limpio se
hizo en M7):

| Paso | Resultado |
|---|---|
| `./mvnw verify` | **BUILD SUCCESS**: 389 unitarios, 238 de integracion, ArchUnit (17 reglas), dominio 100 % |
| Cuatro eventos (borrador, publicado, reprogramado a `Arena Lima`, cancelado) y cola de prueba purgada | los 5 mensajes originales entregados y descartados antes de reemitir |
| `POST /actuator/replay` sin token / con `organizer` / con `admin` | 401 / 403 / 200 `{"events":3,"messages":4,"conflicts":0}` |
| Mensajes reemitidos | el reprogramado con venue y fecha nuevos; el cancelado como `EventPublished` y `EventCancelled` (motivo incluido); el borrador, ninguno |
| Paginacion `limit=2` y luego `after=<next>` | `{"events":2,...,"next":"..."}` y `{"events":1,...}` (sin `next`) |
| Borrador por `eventId` / `admin` en `/api/v1/events` | `{"events":0,...}` / 403 |
| Log del servicio | 0 lineas `ERROR` |

Tras la prueba se bajo el stack con `docker compose down -v`.
