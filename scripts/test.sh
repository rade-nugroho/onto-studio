#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

echo "== engine tests =="
(cd "$ROOT/services/engine" && mvn -q test)

echo "== ui tests =="
(cd "$ROOT/apps/ui" && [ -d node_modules ] || npm install --no-audit --no-fund)
(cd "$ROOT/apps/ui" && npm test)

echo "== shell tests =="
(cd "$ROOT/apps/desktop/src-tauri" && cargo test)

echo "All tests passed."
