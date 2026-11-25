# EventHub

Plataforma de eventos con microservicios: catalogo, reservas y notificaciones en tiempo real.
Proyecto de practica de un backend engineer (Java). Idioma de trabajo: **espanol** para docs y
comentarios de alto nivel; **ingles** para codigo, nombres, commits y logs.

## Flujo de trabajo (OBLIGATORIO)

1. **Plan primero.** Antes de escribir codigo, presenta un plan corto y espera aprobacion.
2. Trabaja por **hitos pequenos**; al terminar cada uno: `./mvnw verify` en verde y un commit.
3. **TDD** en dominio y casos de uso: test primero, luego implementacion.
4. Si algo es ambiguo o contradice este archivo o `contracts/`, **pregunta**; no inventes.
5. No agregues dependencias ni funcionalidades fuera del alcance pedido. Justifica cada dependencia nueva.
6. Nunca commitees secretos, `.env` reales ni credenciales.

## Stack

- Java **21** (records, sealed interfaces, pattern matching, virtual threads donde aplique).
- Spring Boot **4.2.x** (Spring Framework 7, Spring Security 7, **Jackson 3**), fijado en el parent pom
  (decision en `docs/adr/0002-spring-boot-4.md`). Para subir de version, **verifica la vigente** en
  start.spring.io; si implica un salto de linea mayor, pregunta antes.
- Jackson 3: paquetes `tools.jackson.*` (databind, `JsonMapper`); las anotaciones siguen en
  `com.fasterxml.jackson.annotation`. Toda libreria que toque JSON debe soportar Jackson 3
  (Jackson 2 solo puede aparecer en el classpath de **test**). Excepcion vigente: swagger-request-validator
  3.0.0 exige `networknt:json-schema-validator` 2.0.1 (Jackson 2), que no puede convivir con la 3.x
  (`NoSuchMethodError`); por eso los tests de esquemas JSON usan la 2.x. Revisar cuando el validador
  OpenAPI soporte Jackson 3.
- Maven con **Maven Wrapper** (`./mvnw`). Monorepo multi-modulo, versiones centralizadas en el parent (`dependencyManagement`).
- PostgreSQL + Flyway. RabbitMQ en local; **Azure Service Bus** en Azure (fase 2).
- Mensajeria via **Spring Cloud Stream** (abstraccion de broker), usando el BOM de Spring Cloud compatible con la version de Boot.
- Auth: Keycloak en Docker (local) -> Microsoft Entra ID (fase 3). Los servicios son **OAuth2 Resource Server** (JWT).
- Tests: JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit, MockMvc/WebTestClient, networknt
  json-schema-validator **2.x** (ver nota de Jackson abajo), swagger-request-validator
  (artefacto `openapi-request-validator-mockmvc`)
  (conformidad de respuestas con el OpenAPI).

### Convenciones de Spring Boot 4

- Starters con nombres nuevos: `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`,
  `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-flyway`...
  Los tests de MockMvc se anotan con `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- 422 es `HttpStatus.UNPROCESSABLE_CONTENT` (`UNPROCESSABLE_ENTITY` esta deprecada).
- Los modelos y la configuracion de Jackson salen de `spring.jackson.*` (p. ej.
  `default-property-inclusion: non_null` para omitir campos opcionales).

## Estructura del monorepo

```
eventhub/
  CLAUDE.md
  pom.xml                      # parent (packaging pom)
  docker-compose.yml           # postgres, rabbitmq, keycloak (solo desarrollo local)
  contracts/
    openapi/catalog-api.yaml   # contract-first, fuente de verdad de la API REST
    events/*.schema.json       # JSON Schema de eventos (fuente de verdad de mensajes)
  services/
    catalog-service/           # Spring MVC + JPA
    booking-service/           # Spring MVC + JPA + outbox
    notification-service/      # WebFlux + R2DBC + SSE
  docs/
    prompts/                   # prompts por hito para Claude Code
    adr/                       # decisiones de arquitectura (formato MADR corto)
```

## Arquitectura hexagonal (por servicio)

Paquete base: `com.eventhub.<service>` (p. ej. `com.eventhub.catalog`).

```
domain/            # modelo puro: aggregates, entities, value objects, domain events, excepciones de dominio
application/
  port/in/         # casos de uso (interfaces): PublishEventUseCase, ...
  port/out/        # puertos de salida: EventRepository, DomainEventPublisher, Clock, ...
  service/         # implementacion de casos de uso (@Transactional aqui)
infrastructure/
  adapter/in/web/        # controllers (implementan interfaces generadas por OpenAPI), mappers DTO<->dominio
  adapter/out/persistence/   # entidades JPA, repos Spring Data, mappers JPA<->dominio
  adapter/out/messaging/     # outbox, publisher, consumers
  config/                # beans, seguridad, propiedades
```

**Reglas (se verifican con ArchUnit y rompen el build si se violan):**
- `domain` no depende de Spring, JPA, Jackson ni de ningun framework. Solo JDK.
- `application` depende solo de `domain` y de sus propios puertos. Sin anotaciones de web ni de persistencia (excepto `@Transactional` y `@Service`/`@Component`).
- `infrastructure` depende de `application` y `domain`; nunca al reves.
- Las **entidades JPA son distintas** de los aggregates del dominio; se mapean explicitamente (MapStruct o mappers manuales).
- Los controllers no contienen logica de negocio: traducen DTO -> comando -> caso de uso -> DTO.
- Inyeccion por constructor. Prohibido `@Autowired` en campos.

## Modelado de dominio

- Aggregate roots protegen sus invariantes; los setters publicos estan prohibidos. Cambios por metodos con intencion (`publish()`, `cancel(reason)`).
- Value objects como `record` inmutables con validacion en el constructor compacto (`Money`, ids tipados si aportan).
- Dinero: `BigDecimal` con escala 2 y `RoundingMode.HALF_EVEN`. **Nunca** `double`/`float`. En JSON/OpenAPI viaja como string.
- Tiempo: inyectar `java.time.Clock`; **prohibido** `Instant.now()` directo en dominio/aplicacion. Todo en UTC (`Instant`).
- Ids: `UUID` (v7 si hay libreria adoptada; si no, v4). Generados en la aplicacion, no en la BD.
- Violaciones de reglas -> excepciones de dominio propias (sin dependencias de framework), mapeadas a HTTP en el adapter web.
- Eventos de dominio como `sealed interface` + `record`s.

## API REST

- **Contract-first:** editar primero `contracts/openapi/catalog-api.yaml`; generar interfaces con `openapi-generator-maven-plugin`
  (version >= 7.16; `interfaceOnly`, `useSpringBoot4` + `useJackson3`, Jakarta, sin delegate pattern,
  sin anotaciones swagger/springdoc, `date-time` -> `Instant`, modelos con sufijo `Dto`). Salida en
  `target/generated-sources`, nunca versionada. Los controllers implementan esas interfaces.
- El OpenAPI debe declarar **todos** los codigos que el servicio devuelve en la practica; un test de
  conformidad (swagger-request-validator) lo comprueba contra las respuestas reales.
- Prefijo `/api/v1`. Recursos en plural. Paginacion `page`/`size`.
- Errores: **RFC 9457** `application/problem+json` con `ProblemDetail`; un `@RestControllerAdvice` central. Nunca exponer stack traces.
- Mapeo: validacion de campos -> 400; transicion de estado invalida -> 409; regla de negocio incumplida -> 422; no encontrado o sin visibilidad -> 404; sin rol/duenio -> 403.

## Persistencia

- Esquema **solo por Flyway** (`src/main/resources/db/migration`, `V1__...sql`). `spring.jpa.hibernate.ddl-auto=validate`.
- Una BD por servicio; ningun servicio lee tablas de otro.
- Evitar N+1 (`@EntityGraph`/fetch join explicito). `open-in-view=false`.
- Indices justificados en la migracion que los crea.
- Concurrencia optimista: cada guardado de un aggregate existente sube la version **exactamente en 1** y reclama la fila
  antes de tocar filas hijas (ver `docs/adr/0004-event-versioning.md`); un conflicto es 409 `concurrent-modification`.

## Mensajeria

- Esquemas en `contracts/events/`. Todo mensaje usa el **envelope v1** (`messageId`, `type`, `version`, `occurredAt`, `correlationId`, `producer`, `data`).
- Exchange topic `eventhub.events`. Routing key: `<service>.<aggregate>.<event>.v<version>`
  (ej. `catalog.event.published.v1`). Una cola por consumidor+tema, con **DLQ** y reintentos con backoff.
- **Transactional Outbox:** el aggregate y su fila en `outbox_event` se guardan en la misma transaccion;
  un publicador (poller con `FOR UPDATE SKIP LOCKED`) envia al broker y marca `published_at`. Entrega **at-least-once**.
  - `messageId` = id de la fila outbox, estable entre reintentos. Se marca `published_at` **solo tras la
    confirmacion del broker** (publisher confirm, envio sincronico con timeout).
  - Orden **por agregado**: una fila solo se envia si no hay otra anterior sin publicar del mismo agregado.
    Se procesa en `created_at, id` y el lote se corta en el primer fallo.
  - Fallo -> `attempts`/`last_error`/`next_attempt_at` con backoff exponencial; al llegar al tope de intentos
    la fila queda **aparcada** (sigue bloqueando a su agregado hasta que una persona la resuelva).
  - Metricas: `outbox.pending`, `outbox.parked`, `outbox.oldest.age` (segundos).
  - Un confirm del broker no implica cola destino: sin cola enlazada RabbitMQ confirma y descarta. Los
    consumidores declaran sus colas; los eventos previos no se reenvian.
- **Consumidores idempotentes:** tabla `processed_message(message_id PK)`; ignorar duplicados.
- Cambios de esquema **compatibles hacia atras** dentro de una version; un cambio incompatible crea `v2` (nuevo archivo, nueva routing key).
- Todo productor/consumidor tiene un **test de contrato** que valida payloads contra el JSON Schema.

## Seguridad

- Rutas de lectura publicas solo donde el OpenAPI lo indique (`security: []`); el resto exige JWT.
- Rol `ORGANIZER` en el claim de roles del token (Keycloak: `realm_access.roles`); mapear a `ROLE_*`.
- `organizerId`/`userId` salen del claim `sub`, **nunca** del body.
- Validar propiedad del recurso (un organizador solo modifica sus eventos).
- Operaciones administrativas (p. ej. reemision: `POST /actuator/replay`) exigen el rol `ADMIN`, distinto de `ORGANIZER`, y no
  se publican en el OpenAPI. Reemitir envia el **estado actual** (nunca borradores); los consumidores deben ser
  idempotentes por `eventId` (`docs/adr/0003-event-bootstrap-and-replay.md`).
- Secretos por variables de entorno / Key Vault; nunca en `application.yml` versionado.
- El emisor de tokens se configura con `OIDC_ISSUER_URI` (`spring.security.oauth2.resourceserver.jwt.issuer-uri`),
  sin valor por defecto salvo en el perfil `local`.

## Observabilidad

- Logs estructurados (JSON) con `traceId` y `correlationId`. Sin datos personales en logs.
- Actuator: `health` (liveness/readiness) e `info` publicos; `prometheus` exige token. Tracing (OpenTelemetry) pone `traceId`
  en logs y problem+json; OTLP queda desactivado hasta configurar un endpoint. Endpoints sensibles protegidos.
- Propagar `correlationId` del request HTTP al evento y de ahi a los consumidores: `X-Correlation-Id` (UUID valido o se genera) -> MDC -> `CorrelationIdProvider` -> envelope.

## Testing

- Piramide: unit (dominio, sin Spring) > integracion (Testcontainers: Postgres, RabbitMQ) > pocos E2E.
- Nombres: `should_<resultado>_when_<condicion>`. Un concepto por test. Sin `Thread.sleep` (usar Awaitility).
- Cobertura objetivo: dominio >= 90 %, aplicacion >= 80 % (JaCoCo). No perseguir el numero a costa de tests vacios.
- Tests de arquitectura (ArchUnit) en cada servicio.
- Tests de seguridad: sin token (401), rol incorrecto (403), duenio incorrecto (403/404).

## Estilo y calidad

- Metodos cortos, nombres que revelan intencion, sin comentarios que repitan el codigo; comentar el *por que*.
- Preferir `record`, `Optional` solo en retornos, colecciones inmutables, `switch` con patrones sobre `instanceof` en cadena.
- Sin `System.out`, sin `catch (Exception)` generico, sin `null` como valor de negocio.
- Commits: Conventional Commits (`feat(catalog): ...`, `test: ...`, `chore: ...`). Un commit por hito logico.
- Formato: `spotless` o `maven-checkstyle` configurado en el parent; el build falla si hay violaciones.

## Comandos

```bash
docker compose up -d                 # postgres, rabbitmq, keycloak
./mvnw -q verify                     # compila, tests unitarios + integracion, ArchUnit
./mvnw -pl services/catalog-service -am verify
./mvnw -pl services/catalog-service spring-boot:run -Dspring-boot.run.profiles=local
```

## Fuera de alcance (MVP)

Pagos reales, asientos numerados, reembolsos, multi-moneda en una misma reserva, multi-tenant.
Tras `PUBLISHED`, las zonas de un evento son **inmutables** (solo `reschedule` y `cancel`).
