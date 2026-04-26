set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SAFE_FILE="${1:-build/pit-safe-tests.txt}"
UNSAFE_FILE="${2:-build/pit-unsafe-tests.txt}"
LOG_DIR="build/pit-discovery-logs"

mkdir -p "$(dirname "$SAFE_FILE")" "$LOG_DIR"
: > "$SAFE_FILE"
: > "$UNSAFE_FILE"

./gradlew testClasses >/dev/null

mapfile -t TEST_CLASSES < <(
  find build/classes/kotlin/test -type f -name '*Test.class' ! -name '*$*' \
    | sed 's#^build/classes/kotlin/test/##' \
    | sed 's#/#.#g' \
    | sed 's#\.class$##' \
    | sort
)

TOTAL=${#TEST_CLASSES[@]}
echo "PIT safe-test discovery"
echo "Found $TOTAL test classes"
echo

if [ "$TOTAL" -eq 0 ]; then
  echo "No test classes found. Did testClasses run successfully?" >&2
  exit 1
fi

INDEX=0
for TEST_CLASS in "${TEST_CLASSES[@]}"; do
  INDEX=$((INDEX + 1))
  SAFE_LOG="$LOG_DIR/${TEST_CLASS}.log"
  echo "[$INDEX/$TOTAL] $TEST_CLASS"

  if ./gradlew pitest \
      --no-configuration-cache \
      --rerun-tasks \
      -PpitTargetTests="$TEST_CLASS" \
      -PpitThreads=1 \
      >"$SAFE_LOG" 2>&1; then
    echo "$TEST_CLASS" >> "$SAFE_FILE"
    echo "  SAFE"
  else
    echo "$TEST_CLASS" >> "$UNSAFE_FILE"
    echo "  UNSAFE (see $SAFE_LOG)"
  fi
  echo
done

SAFE_COUNT=$(wc -l < "$SAFE_FILE" | tr -d ' ')
UNSAFE_COUNT=$(wc -l < "$UNSAFE_FILE" | tr -d ' ')

echo "Done"
echo "Safe tests:   $SAFE_COUNT → $SAFE_FILE"
echo "Unsafe tests: $UNSAFE_COUNT → $UNSAFE_FILE"
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SAFE_FILE="build/pit-safe-tests.txt"
UNSAFE_FILE="build/pit-unsafe-tests.txt"

scripts/discover-pit-safe-tests.sh "$SAFE_FILE" "$UNSAFE_FILE"

if [ ! -s "$SAFE_FILE" ]; then
  echo "No PIT-safe tests found. Aborting." >&2
  exit 1
fi

./gradlew pitest \
  --no-configuration-cache \
  --rerun-tasks \
  -PpitSafeTestsFile="$SAFE_FILE" \
  -PpitThreads=1
