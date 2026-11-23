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
- Spring Boot: ultima version estable compatible con Java 21. **Verifica la version vigente** en
  start.spring.io y fijala en el parent pom; si implica un salto de linea mayor, pregunta antes.
- Maven con **Maven Wrapper** (`./mvnw`). Monorepo multi-modulo, versiones centralizadas en el parent (`dependencyManagement`).
- PostgreSQL + Flyway. RabbitMQ en local; **Azure Service Bus** en Azure (fase 2).
- Mensajeria via **Spring Cloud Stream** (abstraccion de broker), usando el BOM de Spring Cloud compatible con la version de Boot.
- Auth: Keycloak en Docker (local) -> Microsoft Entra ID (fase 3). Los servicios son **OAuth2 Resource Server** (JWT).
- Tests: JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit, MockMvc/WebTestClient, networknt json-schema-validator.

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
  (`interfaceOnly`, Spring Boot 3, Jakarta, sin delegate pattern). Los controllers implementan esas interfaces.
- Prefijo `/api/v1`. Recursos en plural. Paginacion `page`/`size`.
- Errores: **RFC 9457** `application/problem+json` con `ProblemDetail`; un `@RestControllerAdvice` central. Nunca exponer stack traces.
- Mapeo: validacion de campos -> 400; transicion de estado invalida -> 409; regla de negocio incumplida -> 422; no encontrado o sin visibilidad -> 404; sin rol/duenio -> 403.

## Persistencia

- Esquema **solo por Flyway** (`src/main/resources/db/migration`, `V1__...sql`). `spring.jpa.hibernate.ddl-auto=validate`.
- Una BD por servicio; ningun servicio lee tablas de otro.
- Evitar N+1 (`@EntityGraph`/fetch join explicito). `open-in-view=false`.
- Indices justificados en la migracion que los crea.

## Mensajeria

- Esquemas en `contracts/events/`. Todo mensaje usa el **envelope v1** (`messageId`, `type`, `version`, `occurredAt`, `correlationId`, `producer`, `data`).
- Exchange topic `eventhub.events`. Routing key: `<service>.<aggregate>.<event>.v<version>`
  (ej. `catalog.event.published.v1`). Una cola por consumidor+tema, con **DLQ** y reintentos con backoff.
- **Transactional Outbox:** el aggregate y su fila en `outbox_event` se guardan en la misma transaccion;
  un publicador (poller con `FOR UPDATE SKIP LOCKED`) envia al broker y marca `published_at`. Entrega **at-least-once**.
- **Consumidores idempotentes:** tabla `processed_message(message_id PK)`; ignorar duplicados.
- Cambios de esquema **compatibles hacia atras** dentro de una version; un cambio incompatible crea `v2` (nuevo archivo, nueva routing key).
- Todo productor/consumidor tiene un **test de contrato** que valida payloads contra el JSON Schema.

## Seguridad

- Rutas de lectura publicas solo donde el OpenAPI lo indique (`security: []`); el resto exige JWT.
- Rol `ORGANIZER` en el claim de roles del token (Keycloak: `realm_access.roles`); mapear a `ROLE_*`.
- `organizerId`/`userId` salen del claim `sub`, **nunca** del body.
- Validar propiedad del recurso (un organizador solo modifica sus eventos).
- Secretos por variables de entorno / Key Vault; nunca en `application.yml` versionado.

## Observabilidad

- Logs estructurados (JSON) con `traceId` y `correlationId`. Sin datos personales en logs.
- Actuator: `health` (liveness/readiness) e `info`; `prometheus`/OTLP via Micrometer. Endpoints sensibles protegidos.
- Propagar `correlationId` del request HTTP al evento y de ahi a los consumidores.

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
