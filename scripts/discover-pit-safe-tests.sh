#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

SAFE_FILE="build/pit-safe-tests.txt"
UNSAFE_FILE="build/pit-unsafe-tests.txt"
LOG_DIR="build/pit-discovery-logs"

mkdir -p build "$LOG_DIR"
: > "$SAFE_FILE"
: > "$UNSAFE_FILE"

echo "PIT safe-test discovery"

./gradlew testClasses >/dev/null

TEST_CLASSES=$(
  find build/classes/kotlin/test -name "*Test.class" \
    | sed "s#build/classes/kotlin/test/##" \
    | sed "s#/#.#g" \
    | sed "s#\.class##" \
    | grep "^com\.damorosodaragona\.jenkinsnotifier\." \
    | grep -v '\$' \
    | sort
)

TOTAL=$(printf "%s\n" "$TEST_CLASSES" | grep -c . || true)
INDEX=0

printf "%s\n" "$TEST_CLASSES" | while IFS= read -r TEST_CLASS; do
  [ -z "$TEST_CLASS" ] && continue

  INDEX=$((INDEX + 1))
  SAFE_LOG="$LOG_DIR/${TEST_CLASS}.log"

  echo "[$INDEX/$TOTAL] Checking $TEST_CLASS"

  if ./gradlew pitest \
      --no-configuration-cache \
      --rerun-tasks --info \
      -PpitTargetTests="$TEST_CLASS" \
      > "$SAFE_LOG" 2>&1; then
    echo "$TEST_CLASS" >> "$SAFE_FILE"
    echo "  SAFE"
  else
    echo "$TEST_CLASS" >> "$UNSAFE_FILE"
    echo "  UNSAFE"
  fi
done

echo
echo "Safe tests:"
cat "$SAFE_FILE" || true

echo
echo "Unsafe tests:"
cat "$UNSAFE_FILE" || true