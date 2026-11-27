# 0005. Inventario como proyeccion local con UPDATE condicional y CHECK

- Estado: aceptada
- Fecha: 2026-11-26
- Alcance: `booking-service`

## Contexto y problema

Reservar es el camino caliente del sistema y tiene una regla que no admite excepciones: **nunca se
venden mas entradas que la capacidad de la zona**, con muchos compradores a la vez y varias instancias.
La capacidad la define catalog (dueno del evento y sus zonas); booking necesita saber cuanto queda sin
preguntarselo a catalog en cada reserva, y sin que una caida de catalog impida vender.

## Alternativas consideradas

Donde vive la capacidad:
1. **Llamada sincrona a catalog** en cada reserva. Acopla la disponibilidad de booking a la de catalog,
   anade latencia y una segunda fuente de verdad del contador. Descartada (CLAUDE.md la prohibe).
2. **Proyeccion local** (`event_projection`, `projected_zone`, `zone_inventory`) alimentada por los eventos
   de catalog (ADR 0008). Booking funciona con catalog caido; el coste es consistencia eventual en la
   capacidad (aceptable: se publica antes de vender y una zona es inmutable tras `PUBLISHED`).

Como se descuenta:
1. **Leer, comprobar y escribir** (con o sin `version` optimista). Dos transacciones leen el mismo
   `reserved`; la optimista las serializa a base de reintentos y 409 bajo contencion, que es justo el
   caso de una zona agotandose.
2. **`SELECT ... FOR UPDATE` y luego escribir.** Correcto, pero mantiene el bloqueo de la fila entre dos
   viajes a la base y obliga a ordenar los bloqueos a mano.
3. **`UPDATE` condicional atomico**: la comprobacion y la escritura en una sola sentencia.
4. Un contador en Redis u otro almacen: un segundo sistema que reconciliar con PostgreSQL. Descartado.

## Decision

Proyeccion local y `UPDATE` condicional, con un `CHECK` como ultima defensa:

```sql
-- reservar
UPDATE zone_inventory SET reserved = reserved + :qty, version = version + 1
 WHERE event_id = :e AND zone_id = :z AND reserved + :qty <= capacity;   -- 0 filas = sin inventario
-- liberar
UPDATE zone_inventory SET reserved = reserved - :qty, version = version + 1
 WHERE event_id = :e AND zone_id = :z AND reserved - :qty >= 0;          -- 0 filas = invariante rota
```

```sql
CONSTRAINT ck_zone_inventory_reserved CHECK (reserved >= 0 AND reserved <= capacity)
```

- La comprobacion y el cambio son **una sentencia**: PostgreSQL bloquea la fila, reevalua el `WHERE` con el
  valor ya confirmado y solo una de dos competidoras gana la ultima entrada. 0 filas afectadas es
  `InsufficientInventoryException` (422 `insufficient-inventory`) al reservar.
- El `CHECK` no es la regla de negocio, es el cinturon: si un bug futuro escribiera `reserved` sin la
  condicion, la base **rechaza** el valor en vez de vender de mas (`SchemaConstraintsIT`:
  `should_refuse_an_update_that_would_oversell`).
- Liberar con 0 filas afectadas no es un caso normal sino una **invariante rota** (se libero algo que no
  estaba reservado): lanza `InventoryInvariantException` y la transaccion se deshace, en lugar de
  "arreglar" el contador en silencio.
- **Orden por `zoneId`**: con varias zonas en una reserva, se actualizan siempre ordenadas, para que dos
  reservas que tocan las mismas zonas en orden distinto no se bloqueen mutuamente (deadlock).
- **Liberar solo en la transicion** que lo justifica (expirar, cancelar, cascada de evento), dentro de la
  misma transaccion que cambia el estado de la reserva; una reserva que ya no esta `PENDING`/`CONFIRMED`
  nunca vuelve a liberar. La reemision de catalog **no reinicia `reserved`**: el upsert de la proyeccion
  solo toca `capacity`.
- **Invariante global**, verificada tras las pruebas de estres y las carreras: en cada zona
  `0 <= reserved <= capacity` y `reserved` = suma de las cantidades de las reservas `PENDING` y
  `CONFIRMED` (`RealApp.assertInventoryInvariant`).

## Consecuencias

- Bajo contencion los reservadores hacen cola en la fila de la zona (lock de fila de PostgreSQL), sin
  reintentos de aplicacion; una zona muy caliente es el cuello de botella. Aceptable para el MVP; el
  siguiente paso seria fragmentar el contador por zona, no cambiar de enfoque.
- El inventario y la reserva se guardan en **la misma transaccion**: o hay reserva con sus entradas
  retenidas o no hay nada.
- La capacidad es eventualmente consistente con catalog; una cancelacion de evento llega por el consumidor
  y su cascada (ADR 0008) libera y reembolsa.

## Evidencia

- `InventoryConcurrencyIT`: 200 compradores por 20 entradas venden exactamente 20; cantidades y ordenes de
  zona mezclados nunca superan la capacidad; la invariante se comprueba al final.
- `SchemaConstraintsIT`: el `CHECK` rechaza `reserved` negativo, sobre capacidad y una actualizacion que
  venderia de mas. `JdbcInventoryRepositoryContractIT` (mismo contrato que la version en memoria).
- Mutacion 03 (`docs/mutations`): liberar dos veces rompe 57 tests.
