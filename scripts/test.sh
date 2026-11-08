#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

echo "== engine tests =="
(cd "$ROOT/services/engine" && mvn -q test)

echo "== ui tests =="
(cd "$ROOT/apps/ui" && [ -d node_modules ] || npm install --no-audit --no-fund)
(cd "$ROOT/apps/ui" && npm test)

echo "== shell tests =="
(cd "$ROOT/apps/desktop/src-tauri" && cargo test)

echo "All tests passed."
