#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

echo "Building engine..."
(cd "$ROOT/services/engine" && mvn -q -DskipTests package)

echo "Starting UI dev server..."
(cd "$ROOT/apps/ui" && [ -d node_modules ] || npm install --no-audit --no-fund)
(cd "$ROOT/apps/ui" && npm run dev) &
VITE_PID=$!
trap 'kill "$VITE_PID" 2>/dev/null || true' EXIT

VITE_READY=0
for _ in $(seq 1 30); do
  if curl -sf -o /dev/null http://localhost:5173/; then
    VITE_READY=1
    break
  fi
  sleep 0.5
done
if [ "$VITE_READY" -ne 1 ]; then
  echo "Error: Vite dev server did not start on http://localhost:5173/ within 15s." >&2
  exit 1
fi

echo "Starting Tauri shell..."
cd "$ROOT/apps/desktop/src-tauri"
cargo run
