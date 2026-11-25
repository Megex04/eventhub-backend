#!/usr/bin/env python3
"""Genera los JSON Schemas versionados de eventos (Draft 2020-12).

Uso: python3 contracts/gen_events.py   (escribe en contracts/events/)
Los .json generados son la fuente de verdad; este script es solo ayuda de autoria.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / "events"
OUT.mkdir(exist_ok=True)
DIALECT = "https://json-schema.org/draft/2020-12/schema"
BASE = "https://eventhub.dev/schemas/events/"


def write(name, schema):
    (OUT / name).write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n")


uuid = {"type": "string", "format": "uuid"}
dt = {"type": "string", "format": "date-time"}
money = {"$ref": "common.v1.schema.json#/$defs/Money"}

# --- common -----------------------------------------------------------------
write("common.v1.schema.json", {
    "$schema": DIALECT,
    "$id": BASE + "common.v1.schema.json",
    "title": "Common definitions v1",
    "$defs": {
        "Money": {
            "type": "object",
            "required": ["amount", "currency"],
            "additionalProperties": False,
            "properties": {
                "amount": {"type": "string", "pattern": r"^\d{1,10}\.\d{2}$"},
                "currency": {"type": "string", "pattern": "^[A-Z]{3}$"},
            },
        }
    },
})

# --- envelope ---------------------------------------------------------------
write("envelope.v1.schema.json", {
    "$schema": DIALECT,
    "$id": BASE + "envelope.v1.schema.json",
    "title": "Event envelope v1",
    "description": "Sobre comun de todos los mensajes. `messageId` identifica el mensaje "
                   "(idempotencia en consumidores); los ids de negocio viajan en `data`.",
    "type": "object",
    "required": ["messageId", "type", "version", "occurredAt", "correlationId", "producer", "data"],
    "properties": {
        "messageId": uuid,
        "type": {"type": "string", "pattern": "^[A-Z][A-Za-z]+$"},
        "version": {"type": "integer", "minimum": 1},
        "occurredAt": dt,
        "correlationId": uuid,
        "causationId": uuid,
        "producer": {"type": "string", "enum": ["catalog-service", "booking-service", "notification-service"]},
        "data": {"type": "object"},
    },
})


def event(file, type_name, producer, routing_key, data_schema, description):
    write(file, {
        "$schema": DIALECT,
        "$id": BASE + file,
        "title": f"{type_name} v1",
        "description": f"{description} Routing key: `{routing_key}`.",
        "allOf": [
            {"$ref": "envelope.v1.schema.json"},
            {
                "type": "object",
                "properties": {
                    "type": {"const": type_name},
                    "version": {"const": 1},
                    "producer": {"const": producer},
                    "data": data_schema,
                },
            },
        ],
    })


def data(required, props):
    return {"type": "object", "required": required, "additionalProperties": False, "properties": props}


# --- catalog-service --------------------------------------------------------
zone = {
    "type": "object",
    "required": ["zoneId", "name", "capacity", "price"],
    "additionalProperties": False,
    "properties": {
        "zoneId": uuid,
        "name": {"type": "string", "minLength": 1},
        "capacity": {"type": "integer", "minimum": 1},
        "price": money,
    },
}

event("event-published.v1.schema.json", "EventPublished", "catalog-service",
      "catalog.event.published.v1",
      data(["eventId", "organizerId", "title", "venue", "startsAt", "endsAt", "zones"], {
          "eventId": uuid, "organizerId": uuid,
          "title": {"type": "string"}, "venue": {"type": "string"},
          "startsAt": dt, "endsAt": dt,
          "zones": {"type": "array", "minItems": 1, "items": zone},
      }),
      "Un evento paso a PUBLISHED. booking-service crea el inventario por zona.")

event("event-cancelled.v1.schema.json", "EventCancelled", "catalog-service",
      "catalog.event.cancelled.v1",
      data(["eventId", "reason", "cancelledAt"], {
          "eventId": uuid, "reason": {"type": "string", "minLength": 3}, "cancelledAt": dt,
      }),
      "Un evento fue cancelado. booking-service cancela reservas activas; notification-service avisa.")

event("event-rescheduled.v1.schema.json", "EventRescheduled", "catalog-service",
      "catalog.event.rescheduled.v1",
      data(["eventId", "startsAt", "endsAt", "venue"], {
          "eventId": uuid, "startsAt": dt, "endsAt": dt, "venue": {"type": "string"},
      }),
      "Un evento publicado cambio de fecha y/o venue.")

# --- booking-service --------------------------------------------------------
item = {
    "type": "object",
    "required": ["zoneId", "quantity", "unitPrice"],
    "additionalProperties": False,
    "properties": {"zoneId": uuid, "quantity": {"type": "integer", "minimum": 1}, "unitPrice": money},
}

event("booking-created.v1.schema.json", "BookingCreated", "booking-service",
      "booking.booking.created.v1",
      data(["bookingId", "userId", "eventId", "items", "total", "expiresAt"], {
          "bookingId": uuid, "userId": uuid, "eventId": uuid,
          "items": {"type": "array", "minItems": 1, "items": item},
          "total": money, "expiresAt": dt,
      }),
      "Reserva creada en PENDING; el inventario queda retenido hasta `expiresAt`.")

event("booking-confirmed.v1.schema.json", "BookingConfirmed", "booking-service",
      "booking.booking.confirmed.v1",
      data(["bookingId", "userId", "eventId", "paymentId", "total", "confirmedAt"], {
          "bookingId": uuid, "userId": uuid, "eventId": uuid, "paymentId": uuid,
          "total": money, "confirmedAt": dt,
      }),
      "Reserva confirmada (pago simulado aprobado).")

event("booking-expired.v1.schema.json", "BookingExpired", "booking-service",
      "booking.booking.expired.v1",
      data(["bookingId", "userId", "eventId", "expiredAt"], {
          "bookingId": uuid, "userId": uuid, "eventId": uuid, "expiredAt": dt,
      }),
      "Reserva PENDING vencida; el inventario fue liberado.")

event("booking-cancelled.v1.schema.json", "BookingCancelled", "booking-service",
      "booking.booking.cancelled.v1",
      data(["bookingId", "userId", "eventId", "reason", "cancelledAt"], {
          "bookingId": uuid, "userId": uuid, "eventId": uuid,
          "reason": {"type": "string", "enum": ["USER_REQUEST", "EVENT_CANCELLED"]},
          "cancelledAt": dt,
      }),
      "Reserva cancelada por el usuario o por cancelacion del evento; inventario liberado.")

event("booking-payment-refunded.v1.schema.json", "BookingPaymentRefunded", "booking-service",
      "booking.booking.payment-refunded.v1",
      data(["bookingId", "userId", "eventId", "paymentId", "amount", "reason", "refundedAt"], {
          "bookingId": uuid, "userId": uuid, "eventId": uuid, "paymentId": uuid, "amount": money,
          "reason": {"type": "string",
                     "enum": ["BOOKING_EXPIRED_DURING_PAYMENT", "BOOKING_CANCELLED", "EVENT_CANCELLED"]},
          "refundedAt": dt,
      }),
      "Se reembolso un pago: la reserva dejo de ser pagable durante el cobro, o se cancelo una reserva CONFIRMED.")

print("Esquemas generados en", OUT)
