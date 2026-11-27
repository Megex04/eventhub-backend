# booking-service

Reservas de eventos: inventario como proyeccion local, pago simulado en mini-saga, outbox
transaccional. (Este README se completa en el hito B8 con entorno, flujo curl y runbooks.)

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
