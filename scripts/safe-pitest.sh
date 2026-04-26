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
