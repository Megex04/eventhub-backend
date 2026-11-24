# 0002. Spring Boot 4.2 (Spring Framework 7, Jackson 3) como base del stack

- Estado: aceptada
- Fecha: 2026-11-24
- Contexto del hito: M1 la fijó en el parent pom; este ADR la documenta despues de haber construido M1-M5.

## Contexto y problema

CLAUDE.md pide la ultima version estable de Spring Boot compatible con Java 21 y preguntar antes de
un salto de linea mayor. La ultima linea estable al empezar el proyecto es Spring Boot **4.2.0**
(Spring Framework 7, Spring Security 7, Jackson 3), con el release train de Spring Cloud **2026.0.0**
construido sobre ella. Boot 3.x seguia siendo una opcion, pero cada servicio nuevo nacido en 3.x tendria que
migrar a Boot 4 mas adelante.

## Alternativas consideradas

1. **Spring Boot 3.5.x** (Jackson 2). Ecosistema de librerias de terceros mas maduro; migracion
   a Boot 4 pendiente mas adelante, con los servicios ya escritos.
2. **Spring Boot 4.2.x** (Jackson 3). Menos librerias de terceros listas; sin deuda de migracion.

## Decision

Spring Boot **4.2.0** en el parent pom (`spring-boot-starter-parent`), con Spring Cloud **2026.0.0**
importado por BOM en `dependencyManagement`. Todos los servicios heredan estas versiones.

## Consecuencias

- **Jackson 3** (`tools.jackson.*`) es el JSON por defecto. Las anotaciones siguen en
  `com.fasterxml.jackson.annotation`. Las librerias de JSON deben soportarlo:
  `networknt/json-schema-validator` **3.x** (su linea Jackson 3) en lugar de la 2.x.
- **Generador OpenAPI >= 7.16** con `useSpringBoot4` y `useJackson3` (sustituyen a `useSpringBoot3`).
  Se desactivan las anotaciones swagger/springdoc para no sumar dependencias.
- **Starters renombrados** (`spring-boot-starter-webmvc`, `...-webmvc-test`, `...-flyway`,
  `...-security-oauth2-resource-server`) y paquetes de test movidos
  (`org.springframework.boot.webmvc.test.autoconfigure`).
- `HttpStatus.UNPROCESSABLE_ENTITY` esta deprecada; se usa `UNPROCESSABLE_CONTENT` (sigue siendo 422).
- Validacion de parametros de query en interfaces generadas con `@Validated`: el proxy lanza
  `ConstraintViolationException` (no `HandlerMethodValidationException`); el handler central lo cubre.
- **Riesgo asumido:** el soporte de Jackson 3 / Spring Framework 7 en librerias de test de terceros
  (p. ej. `swagger-request-validator`) debe comprobarse antes de adoptarlas; si una no lo soporta, se
  usa su nucleo con un adaptador propio o se descarta, y se anota aqui.
- Subir a una linea mayor posterior (Boot 5) vuelve a requerir confirmacion explicita, como dice CLAUDE.md.
