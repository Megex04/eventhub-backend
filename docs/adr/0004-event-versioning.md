# 0004. Como se gestiona la version de un evento (concurrencia optimista)

- Estado: aceptada
- Fecha: 2026-11-24

## Contexto y problema

Dos peticiones pueden cambiar el mismo evento a la vez (dos organizadores, o un `addZone` contra un
`publish`). Cada una decide sobre el estado que cargo: si publicar exige al menos una zona, la
decision depende de las zonas que habia al leer. La segunda escritura no puede pisar a la primera sin
saberlo: debe fallar con **409** (`EventVersionConflictException`).

Lo resuelve una columna `version` en `event`. El problema es que el mecanismo por defecto de JPA
(`@Version` con deteccion de cambios) no basta:

1. **Cambios solo en zonas.** Las zonas son filas hijas (lado inverso de la relacion). Si no cambia
   ninguna columna de `event`, Hibernate no emite `UPDATE` y la version no sube: dos `addZone`
   concurrentes ganan los dos. Con un reloj que no avanza (mismo `updated_at`) pasaba de verdad.
2. **Deadlock.** Cada escritor insertaba su zona antes de actualizar la fila de `event`. La restriccion
   de unicidad de nombres de zona esta diferida (se comprueba en el commit) y cada commit esperaba al
   otro: PostgreSQL abortaba uno con `deadlock detected`.
3. **Unicidad diferida.** Un duplicado de nombre confirmado por otra transaccion fallaba en el commit,
   fuera del adaptador, y llegaba al cliente como 500.

## Alternativas consideradas

1. **Confiar en la deteccion de cambios de Hibernate.** No cubre el caso de solo zonas (punto 1).
2. **`OPTIMISTIC_FORCE_INCREMENT`.** Sube la version, pero en el commit, despues de insertar las filas
   hijas (no evita el deadlock) y, si la fila ya estaba sucia, la sube dos veces.
3. **Incremento manual con SQL** (`UPDATE ... SET version = version + 1 WHERE version = ?`). Funciona,
   pero hay que sincronizar la version en memoria con Hibernate, que a su vez intenta subirla.
4. **`PESSIMISTIC_FORCE_INCREMENT` al empezar el guardado, y Hibernate sin incremento propio.**

## Decision

Opcion 4. En `JpaEventRepository.save` de un evento existente:

1. Se compara la version del aggregate con la almacenada (`requireCurrent`).
2. **`claimRow`**: `lock(PESSIMISTIC_FORCE_INCREMENT)` ejecuta de inmediato
   `UPDATE event SET version = v+1 WHERE id = ? AND version = v`. Esa sentencia bloquea la fila del
   evento: el segundo escritor espera al primero y, si este confirmo, su `UPDATE` no encuentra la
   version esperada y falla. **La version sube siempre, cambie o no una columna**, y **antes** de tocar
   filas hijas, asi que no hay deadlock.
3. `copyState` y `flush` aplican los cambios. Las columnas mutables de `EventEntity` llevan
   `@OptimisticLock(excluded = true)`: el `UPDATE` sucio de Hibernate **no** vuelve a subir la version.
   Resultado: **exactamente +1 por guardado**.
4. `failFastOnDuplicateZoneName`: `SET CONSTRAINTS uq_zone_event_name IMMEDIATE` y de nuevo `DEFERRED`.
   Un duplicado de nombre que otra transaccion confirmo falla **dentro** de `save`, y se traduce a
   `EventVersionConflictException` (409), no a un fallo de commit.

Un 409 siempre significa "recarga y reintenta"; el `type` del problema es `concurrent-modification`.

## Evidencia (tests y mutaciones)

- `VersioningIT`: +1 por guardado con cambio de columnas, +1 con solo zonas (comprobando que
  `updated_at` no cambio), y dos actualizaciones de solo titulo que chocan (1 gana, 1 conflicto,
  version final 1).
- `ZoneConcurrencyIT`: `addZone` contra `addZone` (nombres distintos, mismo nombre, reloj congelado) y
  `addZone` contra `publish`; en cada caso un ganador, un conflicto y version final 1.
- `ConcurrentModificationIT`: el 409 por version obsoleta y por unicidad diferida llega al cliente con
  su tipo propio (y sin el arreglo del punto 4 daba 500).
- Mutaciones comprobadas: **quitar `claimRow`** rompe 9 tests (incluidas carreras que dejaban ganar a
  los dos escritores); **quitar `@OptimisticLock(excluded)`** rompe 5 (la version subia de dos en dos).

## Consecuencias

- Los escritores de un mismo evento se serializan en la fila de `event` hasta el commit. Es lo
  esperado para un agregado; no afecta a eventos distintos.
- La version no es un contador de "cambios de columna": es un contador de guardados. Cualquier codigo
  que escriba en `event` o en sus zonas fuera de `JpaEventRepository.save` rompe la garantia (por eso
  una regla de ArchUnit impide que nada fuera del adaptador use las clases de persistencia).
- Se depende de una anotacion especifica de Hibernate (`@OptimisticLock`) y de PostgreSQL
  (`SET CONSTRAINTS`). Ambos estan confinados al adaptador de persistencia.
- `delete` usa `requireCurrent` y `DELETE ... WHERE version`; cubierto por el contrato del repositorio.
