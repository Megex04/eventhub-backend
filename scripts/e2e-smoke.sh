#!/usr/bin/env bash
# End-to-end smoke test of catalog-service + booking-service against the real docker-compose stack
# (PostgreSQL, RabbitMQ, Keycloak). Needs bash, curl and Docker; no jq (fields are parsed with grep/cut).
#
#   scripts/e2e-smoke.sh            # builds the jars if missing, starts both services, runs, stops them
#   E2E_DOWN=1 scripts/e2e-smoke.sh # also `docker compose down -v` at the end
#
# Scenario A: the whole flow with an organizer and a customer
#   create + publish an event -> availability in booking -> book -> pay -> BookingConfirmed in RabbitMQ
#   -> cancel -> BookingPaymentRefunded in RabbitMQ -> tickets back in the availability.
# Scenario B: the late consumer
#   booking down and its queue absent -> publish an event (RabbitMQ drops it: no queue is bound) ->
#   start booking -> the availability does not know the event -> POST /actuator/replay on catalog (ADMIN)
#   -> the availability appears.
#
# Exit code 0 only if every check passed. Logs of the services it starts: $E2E_LOGS (default: a temp dir).
set -uo pipefail

cd "$(dirname "$0")/.."

CATALOG_URL=${CATALOG_URL:-http://localhost:8081}
BOOKING_URL=${BOOKING_URL:-http://localhost:8082}
KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8080}
RABBIT_API=${RABBIT_API:-http://localhost:15672/api}
RABBIT_AUTH=${RABBIT_AUTH:-eventhub:eventhub_dev}   # development defaults of docker-compose.yml
E2E_LOGS=${E2E_LOGS:-$(mktemp -d)}

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-eventhub_dev}
export RABBITMQ_DEFAULT_PASS=${RABBITMQ_DEFAULT_PASS:-eventhub_dev}

CATALOG_PID=""
BOOKING_PID=""
FAILURES=0
CHECKS=0
BODY=""
CODE=""

# ------------------------------------------------------------------------------------- helpers

say() { printf '\n== %s\n' "$*"; }

check() {  # check <description> <expected> <actual>
  CHECKS=$((CHECKS + 1))
  if [ "$2" = "$3" ]; then
    printf '  ok    %s\n' "$1"
  else
    printf '  FAIL  %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
    FAILURES=$((FAILURES + 1))
  fi
}

check_contains() {  # check_contains <description> <needle> <haystack>
  CHECKS=$((CHECKS + 1))
  if printf '%s' "$3" | grep -q -- "$2"; then
    printf '  ok    %s\n' "$1"
  else
    printf '  FAIL  %s (no "%s" in: %.200s)\n' "$1" "$2" "$3"
    FAILURES=$((FAILURES + 1))
  fi
}

# req <METHOD> <url> [curl args...]  -> sets CODE and BODY
req() {
  local method=$1 url=$2
  shift 2
  local out
  out=$(curl -s -w '\n%{http_code}' -X "$method" "$url" "$@")
  CODE=${out##*$'\n'}
  BODY=${out%$'\n'*}
}

field() { printf '%s' "$BODY" | grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

token() {  # token <user> <password>
  curl -s -X POST "$KEYCLOAK_URL/realms/eventhub/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=eventhub-web -d username="$1" -d password="$2" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
}

rabbit() { curl -s -u "$RABBIT_AUTH" -H 'content-type: application/json' "$@"; }

wait_until() {  # wait_until <seconds> <description> <command...>
  local limit=$1 what=$2
  shift 2
  local i
  for ((i = 0; i < limit * 2; i++)); do
    if "$@" > /dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  printf '  FAIL  timed out after %ss waiting for: %s\n' "$limit" "$what"
  FAILURES=$((FAILURES + 1))
  return 1
}

healthy() { curl -s -f "$1/actuator/health" > /dev/null; }

jar_of() { ls "services/$1/target/$1"-*.jar 2> /dev/null | grep -v '\.original$' | head -1; }

start_service() {  # start_service <name> <port> -> sets <NAME>_PID
  local name=$1 jar
  jar=$(jar_of "$name")
  if [ -z "$jar" ]; then
    echo "Building the jars (no tests)..."
    ./mvnw -q -DskipTests package || { echo "build failed" >&2; exit 2; }
    jar=$(jar_of "$name")
  fi
  java -jar "$jar" --spring.profiles.active=local > "$E2E_LOGS/$name.log" 2>&1 &
  local pid=$!
  if [ "$name" = catalog-service ]; then CATALOG_PID=$pid; else BOOKING_PID=$pid; fi
}

stop_booking() {
  if [ -n "$BOOKING_PID" ]; then
    kill "$BOOKING_PID" 2> /dev/null
    wait "$BOOKING_PID" 2> /dev/null
    BOOKING_PID=""
  fi
}

cleanup() {
  stop_booking
  if [ -n "$CATALOG_PID" ]; then kill "$CATALOG_PID" 2> /dev/null; wait "$CATALOG_PID" 2> /dev/null; fi
  [ "${E2E_DOWN:-0}" = 1 ] && docker compose down -v > /dev/null 2>&1
}
trap cleanup EXIT
trap 'exit 130' INT TERM

iso_in_days() { date -u -d "+$1 days" +%Y-%m-%dT%H:%M:%SZ 2> /dev/null || date -u -v+"$1"d +%Y-%m-%dT%H:%M:%SZ; }

# A random (version 4) UUID with nothing but od and /dev/urandom: Git Bash has no uuidgen or /proc.
new_uuid() {
  local h
  h=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $((8 + 0x${h:16:1} % 4)) "${h:17:3}" "${h:20:12}"
}

# Creates and publishes an event with one zone of 50 tickets. Sets EVENT_ID and ZONE_ID.
publish_event() {  # publish_event <title>
  req POST "$CATALOG_URL/api/v1/events" -H "Authorization: Bearer $ORG" -H 'Content-Type: application/json' \
    -d '{"title":"'"$1"'","venue":"Estadio Nacional","startsAt":"'"$(iso_in_days 30)"'","endsAt":"'"$(iso_in_days 31)"'"}'
  check "create event -> 201" 201 "$CODE"
  EVENT_ID=$(field id)
  req POST "$CATALOG_URL/api/v1/events/$EVENT_ID/zones" -H "Authorization: Bearer $ORG" -H 'Content-Type: application/json' \
    -d '{"name":"General","price":{"amount":"150.00","currency":"PEN"},"capacity":50}'
  check "add zone -> 201" 201 "$CODE"
  ZONE_ID=$(field id)
  req POST "$CATALOG_URL/api/v1/events/$EVENT_ID/publish" -H "Authorization: Bearer $ORG"
  check "publish event -> 200" 200 "$CODE"
}

availability() { curl -s -o "$E2E_LOGS/availability.json" -w '%{http_code}' "$BOOKING_URL/api/v1/availability/$1"; }

availability_ok() { [ "$(availability "$1")" = 200 ]; }

available_tickets() {  # available_tickets <eventId> -> number from the availability
  curl -s "$BOOKING_URL/api/v1/availability/$1" | grep -o '"available":[0-9]*' | head -1 | cut -d: -f2
}

has_message() { messages_in e2e.booking | grep -q "$1"; }

messages_in() {  # messages_in <queue> -> bodies without consuming them
  rabbit -X POST "$RABBIT_API/queues/%2F/$1/get" -d '{"count":50,"ackmode":"ack_requeue_true","encoding":"auto"}'
}

# ---------------------------------------------------------------------------------- preconditions

say "Preconditions"
command -v curl > /dev/null || { echo "curl is required" >&2; exit 2; }
docker compose up -d --wait > /dev/null 2>&1 || { echo "docker compose up failed" >&2; exit 2; }
docker compose exec -T postgres psql -U eventhub -d catalog -tc "SELECT 1 FROM pg_database WHERE datname='booking'" \
  | grep -q 1 || docker compose exec -T postgres psql -U eventhub -d catalog -c "CREATE DATABASE booking;" > /dev/null
echo "  docker compose is up (logs of the services: $E2E_LOGS)"

if curl -s -o /dev/null "$BOOKING_URL/actuator/health" || (exec 3<> /dev/tcp/127.0.0.1/8082) 2> /dev/null; then
  echo "Something already listens on the booking port (8082): stop it, the script starts its own." >&2
  exit 2
fi
if ! healthy "$CATALOG_URL"; then
  start_service catalog-service
  wait_until 90 "catalog-service to be healthy" healthy "$CATALOG_URL" || exit 1
fi
start_service booking-service
wait_until 90 "booking-service to be healthy" healthy "$BOOKING_URL" || exit 1

ORG=$(token organizer organizer_dev)
CUS=$(token customer1 customer1_dev)
ADM=$(token admin admin_dev)
check "tokens issued by Keycloak" "yes" "$([ -n "$ORG" ] && [ -n "$CUS" ] && [ -n "$ADM" ] && echo yes || echo no)"

# An observation queue for booking's events. Created before booking events exist: with no bound queue
# RabbitMQ confirms and drops.
rabbit -X PUT "$RABBIT_API/exchanges/%2F/eventhub.events" -d '{"type":"topic","durable":true}' > /dev/null
rabbit -X DELETE "$RABBIT_API/queues/%2F/e2e.booking" > /dev/null
rabbit -X PUT "$RABBIT_API/queues/%2F/e2e.booking" -d '{"durable":true,"auto_delete":false}' > /dev/null
rabbit -X POST "$RABBIT_API/bindings/%2F/e/eventhub.events/q/e2e.booking" -d '{"routing_key":"booking.#"}' > /dev/null

# ------------------------------------------------------------------------------------ scenario A

say "A. Organizer publishes, customer books, pays and cancels"
publish_event "E2E Rock Fest $(date +%s)"
wait_until 30 "the event in booking availability" availability_ok "$EVENT_ID"
check "availability shows the 50 tickets" 50 "$(available_tickets "$EVENT_ID")"

KEY=$(new_uuid)
req POST "$BOOKING_URL/api/v1/bookings" -H "Authorization: Bearer $CUS" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"eventId":"'"$EVENT_ID"'","items":[{"zoneId":"'"$ZONE_ID"'","quantity":2}]}'
check "book 2 tickets -> 201" 201 "$CODE"
BOOKING_ID=$(field id)
check_contains "the booking is PENDING" '"status":"PENDING"' "$BODY"
check "2 tickets held" 48 "$(available_tickets "$EVENT_ID")"

req POST "$BOOKING_URL/api/v1/bookings" -H "Authorization: Bearer $CUS" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"eventId":"'"$EVENT_ID"'","items":[{"zoneId":"'"$ZONE_ID"'","quantity":2}]}'
check "the same key and body answers 200 with the same booking" "200 $BOOKING_ID" "$CODE $(field id)"

req POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/pay" -H "Authorization: Bearer $CUS" \
  -H 'Content-Type: application/json' -d '{"paymentToken":"tok_approved"}'
check "pay -> 200" 200 "$CODE"
check_contains "the booking is CONFIRMED" '"status":"CONFIRMED"' "$BODY"

wait_until 20 "BookingConfirmed in RabbitMQ" has_message confirmed
CONFIRMED_MSG=$(messages_in e2e.booking)
check_contains "BookingConfirmed carries the booking id" "$BOOKING_ID" "$(printf '%s' "$CONFIRMED_MSG" | grep confirmed | head -1)"
check_contains "BookingConfirmed carries a paymentId" 'paymentId' "$CONFIRMED_MSG"

req POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/cancel" -H "Authorization: Bearer $CUS"
check "cancel -> 200" 200 "$CODE"
check_contains "the booking is CANCELLED" '"status":"CANCELLED"' "$BODY"
wait_until 20 "BookingPaymentRefunded in RabbitMQ" has_message payment-refunded
check_contains "BookingPaymentRefunded names the booking" "$BOOKING_ID" "$(messages_in e2e.booking | grep 'payment-refunded' | head -1)"
check "the tickets are back" 50 "$(available_tickets "$EVENT_ID")"

# ------------------------------------------------------------------------------------ scenario B

FIRST_EVENT=$EVENT_ID

say "B. Late consumer: booking was down when the event was published"
stop_booking
check "booking is down" "no" "$(healthy "$BOOKING_URL" && echo yes || echo no)"
# booking's queue is durable, so a stopped booking would still receive messages: delete it to model a
# consumer that had no queue when the event was published (it is declared again on startup).
rabbit -X DELETE "$RABBIT_API/queues/%2F/booking-catalog-events" > /dev/null
rabbit -X DELETE "$RABBIT_API/queues/%2F/booking-catalog-events.dlq" > /dev/null
publish_event "E2E Late Fest $(date +%s)"
LATE_EVENT=$EVENT_ID
sleep 3   # let catalog's relay send it: with no bound queue, RabbitMQ confirms and drops it

start_service booking-service
wait_until 90 "booking-service to be healthy again" healthy "$BOOKING_URL" || exit 1
sleep 3
check "booking does not know the event (the message was dropped)" 404 "$(availability "$LATE_EVENT")"

req POST "$CATALOG_URL/actuator/replay" -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' -d '{}'
check "replay with an ADMIN token -> 200" 200 "$CODE"
wait_until 30 "the late event in booking availability" availability_ok "$LATE_EVENT"
check "the late event is bookable after the replay (50 tickets)" 50 "$(available_tickets "$LATE_EVENT")"
check "the replay did not reopen or reset the first event (50 tickets, none reserved)" 50 "$(available_tickets "$FIRST_EVENT")"

# --------------------------------------------------------------------------------------- summary

say "Summary"
printf '  %s checks, %s failed\n' "$CHECKS" "$FAILURES"
[ "$FAILURES" -eq 0 ]
