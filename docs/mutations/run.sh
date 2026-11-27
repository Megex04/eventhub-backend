#!/usr/bin/env bash
# Applies each mutation patch of this folder to booking-service, runs its whole test suite against
# the real PostgreSQL/RabbitMQ (Testcontainers), reports which tests broke, and puts the code back.
# A mutation that breaks nothing means the suite cannot see that bug: the script then exits 1.
#
#   docs/mutations/run.sh            # every mutation
#   docs/mutations/run.sh 02         # only the one whose file name starts with 02
#
# Needs Docker and a clean working tree (it refuses to start otherwise, so it can never swallow
# your changes).
set -uo pipefail

cd "$(dirname "$0")/../.."
REPORTS=services/booking-service/target

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "The working tree has uncommitted changes; commit or stash them first." >&2
  exit 2
fi

survivors=0
applied=""

# Always put the code back, whatever happens: the suite failing, Ctrl+C, kill, or an error here.
restore() {
  if [ -n "$applied" ]; then
    git apply -R "$applied" 2>/dev/null || git checkout -- services/booking-service/src/main
    applied=""
  fi
}
trap restore EXIT
trap 'restore; exit 130' INT TERM
printf '| Mutation | Failing tests | Test classes that notice |\n|---|---|---|\n'

for patch in docs/mutations/${1:-}*.patch; do
  name=$(basename "$patch" .patch)
  git apply "$patch" && applied="$patch" || { echo "| $name | patch does not apply | |"; survivors=$((survivors + 1)); continue; }

  rm -rf "$REPORTS/surefire-reports" "$REPORTS/failsafe-reports"
  log=$(./mvnw -pl services/booking-service -am verify \
        -Dmaven.test.failure.ignore=true -Dspotless.check.skip=true -Djacoco.skip=true 2>&1)

  if echo "$log" | grep -q "COMPILATION ERROR"; then
    echo "| $name | does not compile | |"
    survivors=$((survivors + 1))
  else
    failing=$(cat "$REPORTS"/surefire-reports/*.txt "$REPORTS"/failsafe-reports/*.txt 2>/dev/null \
      | grep -E '^Tests run:.*(Failures|Errors)' \
      | sed -E 's/.*Failures: ([0-9]+), Errors: ([0-9]+).*/\1 \2/' \
      | awk '{ n += $1 + $2 } END { print n + 0 }')
    classes=$(grep -lE 'Failures: [1-9]|Errors: [1-9]' "$REPORTS"/surefire-reports/*.txt \
      "$REPORTS"/failsafe-reports/*.txt 2>/dev/null \
      | xargs -n1 basename 2>/dev/null | sed -E 's/\.txt$//; s/.*\.//' | sort -u | paste -sd, -)
    echo "| $name | $failing | ${classes:-none} |"
    [ "$failing" -eq 0 ] && survivors=$((survivors + 1))
  fi

  restore
done

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "WARNING: the tree is not clean after the run." >&2
fi
[ "$survivors" -eq 0 ] || { echo "$survivors mutation(s) survived." >&2; exit 1; }
