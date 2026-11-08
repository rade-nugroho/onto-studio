#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

echo "Building engine..."
(cd "$ROOT/services/engine" && mvn -q -DskipTests package)

echo "Starting UI dev server..."
(cd "$ROOT/apps/ui" && [ -d node_modules ] || npm install --no-audit --no-fund)
(cd "$ROOT/apps/ui" && npm run dev) &
VITE_PID=$!
trap 'kill "$VITE_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
  if curl -sf -o /dev/null http://localhost:5173/; then
    break
  fi
  sleep 0.5
done

echo "Starting Tauri shell..."
cd "$ROOT/apps/desktop/src-tauri"
cargo run
